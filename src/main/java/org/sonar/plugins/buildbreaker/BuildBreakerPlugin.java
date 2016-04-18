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

import org.sonar.api.PropertyType;
import org.sonar.api.SonarPlugin;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.resources.Qualifiers;

import java.util.Arrays;
import java.util.List;

public class BuildBreakerPlugin extends SonarPlugin {

  private static final String LOGGING_SUB_CATEGORY = "Logging";

  public static final String SKIP_KEY = "sonar.buildbreaker.skip";

  public static final String QUERY_MAX_ATTEMPTS_KEY = "sonar.buildbreaker.queryMaxAttempts";

  public static final String QUERY_INTERVAL_KEY = "sonar.buildbreaker.queryInterval";

  public static final String TOTAL_WAIT_TIME_DESCRIPTION = "Total wait time is <code>"
    + BuildBreakerPlugin.QUERY_MAX_ATTEMPTS_KEY + " * " + BuildBreakerPlugin.QUERY_INTERVAL_KEY + "</code>.";

  public static final String BUILD_BREAKER_LOG_STAMP = "[BUILD BREAKER] ";

  public static final String FORBIDDEN_CONF_KEY = "sonar.buildbreaker.forbiddenConf";

  public static final String DRY_RUN_KEY = "sonar.buildbreaker.dryRun";

  public static final String ERROR_THRESHOLD_LOG_STAMP_KEY = "sonar.buildbreaker.errorThresholdLogStamp";

  public static final String WARN_THRESHOLD_LOG_STAMP_KEY = "sonar.buildbreaker.warnThresholdLogStamp";

  @Override
  public List getExtensions() {
    PropertyDefinition skipProperty = PropertyDefinition.builder(SKIP_KEY)
      .defaultValue("false")
      .name("Skip quality gate check")
      .description(
        "If set to true, the quality gate is not checked.  By default the build will break if the project does not pass the quality gate.")
      .type(PropertyType.BOOLEAN)
      .onQualifiers(Qualifiers.PROJECT)
      .build();

    PropertyDefinition queryMaxAttemptsProperty = PropertyDefinition.builder(QUERY_MAX_ATTEMPTS_KEY)
      .defaultValue("30")
      .name("API query max attempts")
      .description(
        "The maximum number of queries to the API when waiting for report processing.  The build will break if this is reached.")
      .type(PropertyType.INTEGER)
      .onQualifiers(Qualifiers.PROJECT)
      .build();

    PropertyDefinition queryIntervalProperty = PropertyDefinition.builder(QUERY_INTERVAL_KEY)
      .defaultValue("10000")
      .name("API query interval (ms)")
      .description("The interval between queries to the API when waiting for report processing.<br/>"
        + BuildBreakerPlugin.TOTAL_WAIT_TIME_DESCRIPTION)
      .type(PropertyType.INTEGER)
      .onQualifiers(Qualifiers.PROJECT)
      .build();

    PropertyDefinition forbiddenConfProperty = PropertyDefinition.builder(FORBIDDEN_CONF_KEY)
      .name("Forbidden configuration parameters")
      .description("Comma-separated list of <code>key=value</code> pairs that should break the build.")
      .build();

    PropertyDefinition dryRunProperty = PropertyDefinition.builder(DRY_RUN_KEY)
      .defaultValue("false")
      .name("Execute but do not fail build")
      .description(
        "If set to true, the quality gate will be checked for alert or error but the build will not be broken. Useful for checking in the log if the build has reached a threshold.")
      .type(PropertyType.BOOLEAN)
      .onQualifiers(Qualifiers.PROJECT)
      .build();

    PropertyDefinition errorLogStampProp = PropertyDefinition.builder(ERROR_THRESHOLD_LOG_STAMP_KEY)
      .defaultValue("BUILD_BREAKER_ERROR_THRESHOLD")
      .name("Error Threshold Log Stamp")
      .description("String to be logged when an ERROR threshold is reached.")
      .onQualifiers(Qualifiers.PROJECT)
      .subCategory(LOGGING_SUB_CATEGORY)
      .build();

    PropertyDefinition warnLogStampProp = PropertyDefinition.builder(WARN_THRESHOLD_LOG_STAMP_KEY)
      .defaultValue("BUILD_BREAKER_WARN_THRESHOLD")
      .name("Warn Threshold Log Stamp")
      .description("String to be logged when a WARN threshold is reached.")
      .onQualifiers(Qualifiers.PROJECT)
      .subCategory(LOGGING_SUB_CATEGORY)
      .build();

    return Arrays.asList(
      skipProperty,
      queryMaxAttemptsProperty,
      queryIntervalProperty,
      forbiddenConfProperty,
      dryRunProperty,
      errorLogStampProp,
      warnLogStampProp,
      ForbiddenConfigurationBreaker.class,
      QualityGateBreaker.class);
  }

}
