/*
 * SonarQube Build Breaker Plugin
 * Copyright (C) 2009-2016 Matthew DeTullio and contributors
 * mailto:sonarqube@googlegroups.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.buildbreaker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Files;
import org.sonar.api.CoreProperties;
import org.sonar.api.batch.BuildBreaker;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.config.Settings;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;
import org.sonar.api.resources.Project;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarqube.ws.MediaTypes;
import org.sonarqube.ws.WsCe.TaskResponse;
import org.sonarqube.ws.WsCe.TaskStatus;
import org.sonarqube.ws.WsQualityGates.ProjectStatusWsResponse;
import org.sonarqube.ws.WsQualityGates.ProjectStatusWsResponse.Comparator;
import org.sonarqube.ws.WsQualityGates.ProjectStatusWsResponse.Condition;
import org.sonarqube.ws.WsQualityGates.ProjectStatusWsResponse.ProjectStatus;
import org.sonarqube.ws.WsQualityGates.ProjectStatusWsResponse.Status;
import org.sonarqube.ws.client.GetRequest;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.HttpWsClient;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsRequest;
import org.sonarqube.ws.client.WsResponse;
import org.sonarqube.ws.client.qualitygate.ProjectStatusWsRequest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;

public class QualityGateBreaker extends BuildBreaker {
  private static final String CLASSNAME = QualityGateBreaker.class.getSimpleName();
  private static final Logger LOGGER = Loggers.get(QualityGateBreaker.class);

  private final FileSystem fileSystem;
  private final Settings settings;

  public QualityGateBreaker(FileSystem fileSystem, Settings settings) {
    this.fileSystem = fileSystem;
    this.settings = settings;
  }

  @Override
  public void executeOn(Project project, SensorContext context) {
    if (!context.analysisMode().isPublish()) {
      LOGGER.debug("{} is disabled ({} != {})", CLASSNAME, CoreProperties.ANALYSIS_MODE, CoreProperties.ANALYSIS_MODE_PUBLISH);
      return;
    }

    if (settings.getBoolean(BuildBreakerPlugin.SKIP_KEY)) {
      LOGGER.debug("{} is disabled ({} = true)", CLASSNAME, BuildBreakerPlugin.SKIP_KEY);
      return;
    }

    execute();
  }

  private void execute() {
    Properties reportTaskProps = loadReportTaskProps();

    HttpConnector httpConnector = new HttpConnector.Builder()
        .url(reportTaskProps.getProperty("serverUrl"))
        .credentials(settings.getString(CoreProperties.LOGIN), settings.getString(CoreProperties.PASSWORD))
        .build();

    WsClient wsClient = new HttpWsClient(httpConnector);

    String analysisId = getAnalysisId(wsClient, reportTaskProps.getProperty("ceTaskId"));

    checkQualityGate(wsClient, analysisId);
  }

  private Properties loadReportTaskProps() {
    File reportTaskFile = new File(fileSystem.workDir(), "report-task.txt");

    Properties reportTaskProps = new Properties();

    try {
      reportTaskProps.load(Files.newReader(reportTaskFile, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to load properties from file " + reportTaskFile, e);
    }

    return reportTaskProps;
  }

  @VisibleForTesting
  String getAnalysisId(WsClient wsClient, String ceTaskId) {
    WsRequest ceTaskRequest = new GetRequest("api/ce/task").setParam("id", ceTaskId).setMediaType(MediaTypes.PROTOBUF);

    int queryMaxAttempts = settings.getInt(BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY);
    int queryInterval = settings.getInt(BuildBreakerPlugin.QUERY_INTERVAL_KEY);

    for (int attempts = 0; attempts < queryMaxAttempts; attempts++) {
      WsResponse wsResponse = wsClient.wsConnector().call(ceTaskRequest);

      try {
        TaskResponse taskResponse = TaskResponse.parseFrom(wsResponse.contentStream());
        TaskStatus taskStatus = taskResponse.getTask().getStatus();

        switch (taskStatus) {
          case IN_PROGRESS:
          case PENDING:
            // Wait the configured interval then retry
            LOGGER.info("Waiting for report processing to complete...");
            Thread.sleep(queryInterval);
            break;
          case SUCCESS:
            // Exit
            return taskResponse.getTask().getAnalysisId();
          default:
            throw new IllegalStateException("Report processing did not complete successfully: " + taskStatus);
        }
      } catch (IOException | InterruptedException e) {
        throw new IllegalStateException(e.getMessage(), e);
      }
    }

    LOGGER.error("{}API query limit ({}) reached.  Try increasing {}, {}, or both.",
        BuildBreakerPlugin.BUILD_BREAKER_LOG_STAMP,
        queryMaxAttempts,
        BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY,
        BuildBreakerPlugin.QUERY_INTERVAL_KEY);

    throw new IllegalStateException("Report processing is taking longer than the configured wait limit.");
  }

  @VisibleForTesting
  void checkQualityGate(WsClient wsClient, String analysisId) {
    LOGGER.debug("Requesting quality gate status for analysisId {}", analysisId);
    ProjectStatusWsResponse projectStatusResponse = wsClient.qualityGates().projectStatus(
        new ProjectStatusWsRequest().setAnalysisId(analysisId));

    ProjectStatus projectStatus = projectStatusResponse.getProjectStatus();

    Status status = projectStatus.getStatus();
    LOGGER.info("Quality gate status: {}", status);

    int warnings = logConditions(Status.WARN, projectStatus.getConditionsList()); 
    int errors = logConditions(Status.ERROR, projectStatus.getConditionsList());
    
    if(Status.WARN.equals(status)){
      LOGGER.warn("{}Project reached {} warn thresholds", BuildBreakerPlugin.BUILD_BREAKER_LOG_STAMP, warnings);
      
    }else if(Status.ERROR.equals(status)){
      LOGGER.error("{}Project did not meet {} conditions", BuildBreakerPlugin.BUILD_BREAKER_LOG_STAMP, errors);
    		
      if (!settings.getBoolean(BuildBreakerPlugin.DRY_RUN_KEY)) {
        fail("Project does not pass the quality gate.");
      }
    }
  }

  @VisibleForTesting
  static int logConditions(Status statusToLog, List<Condition> conditionsList) {
    int errors = 0;

    for (Condition condition : conditionsList) {
    	
      Status conditionStatus = condition.getStatus();
      if(statusToLog.equals(conditionStatus )){
        errors++;
        
        if (Status.WARN.equals(conditionStatus)) {
          LOGGER.warn("{}: {} {} {}",
              getMetricName(condition.getMetricKey()),
              condition.getActualValue(),
              getComparatorSymbol(condition.getComparator()),
              condition.getWarningThreshold());
          
        } else if (Status.ERROR.equals(conditionStatus)) {
          LOGGER.error("{}: {} {} {}",
              getMetricName(condition.getMetricKey()),
              condition.getActualValue(),
              getComparatorSymbol(condition.getComparator()),
              condition.getErrorThreshold());
        }
      }
    }

    return errors;
  }

  private static String getMetricName(String metricKey) {
    try {
      Metric metric = CoreMetrics.getMetric(metricKey);
      return metric.getName();
    } catch (NoSuchElementException e) {
      // Custom metric -- use key as name
    }
    return metricKey;
  }

  private static String getComparatorSymbol(Comparator comparator) {
    switch (comparator) {
      case GT:
        return ">";
      case LT:
        return "<";
      case EQ:
        return "=";
      case NE:
        return "!=";
      default:
        return comparator.toString();
    }
  }
}
