/*
 *  Licensed to Metamarkets Group Inc. (Metamarkets) under one
 *  or more contributor license agreements. See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership. Metamarkets licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.druid.emitter.statsd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.metamx.common.logger.Logger;
import com.metamx.emitter.core.Emitter;
import com.metamx.emitter.core.Event;
import com.metamx.emitter.service.ServiceMetricEvent;
import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import com.timgroup.statsd.StatsDClientErrorHandler;

import java.io.IOException;
import java.util.Map;

/**
 */
public class StatsDEmitter implements Emitter
{

  private final static Logger log = new Logger(StatsDEmitter.class);
  private final static String DRUID_METRIC_SEPARATOR = "\\/";
  private final static String STATSD_SEPARATOR = ":|\\|";
  private final static String UNWANTED_CHARS = "\\.|:";

  private final StatsDClient statsd;
  private final StatsDEmitterConfig config;
  private final DimensionConverter converter;

  public StatsDEmitter(StatsDEmitterConfig config, ObjectMapper mapper) {
    this.config = config;
    this.converter = new DimensionConverter(mapper, config.getDimensionMapPath());
    statsd = new NonBlockingStatsDClient(
        config.getPrefix(),
        config.getHostname(),
        config.getPort(),
        new StatsDClientErrorHandler()
        {
          private int exceptionCount = 0;
          @Override
          public void handle(Exception exception)
          {
            if (exceptionCount % 1000 == 0) {
              log.error(exception, "Error sending metric to StatsD.");
            }
            exceptionCount += 1;
          }
        }
    );
  }


  @Override
  public void start() {}

  public static final class NameBuilder {

    private final ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
    private final String SEPARATOR;
    private final String REPLACEMENT_CHAR;

    public NameBuilder(String separator, String replacementChar) {
      SEPARATOR = separator;
      REPLACEMENT_CHAR = replacementChar;
    };

    private String escape(String part) {
      return part.replaceAll(UNWANTED_CHARS, REPLACEMENT_CHAR);
    }

    public final NameBuilder add(String part) {
      builder.add(escape(part));
      return this;
    }

    public final String build() {
      return Joiner.on(SEPARATOR)
              .join(builder.build())
              .replaceAll(DRUID_METRIC_SEPARATOR, SEPARATOR)
              .replaceAll(STATSD_SEPARATOR, SEPARATOR);
    }

    public ImmutableList<String> getParts() {
      return builder.build();
    }
  }

  @Override
  public void emit(Event event)
  {
    if (event instanceof ServiceMetricEvent) {
      ServiceMetricEvent metricEvent = (ServiceMetricEvent) event;
      String host = metricEvent.getHost();
      String service = metricEvent.getService();
      String metric = metricEvent.getMetric();
      Map<String, Object> userDims = metricEvent.getUserDims();
      Number value = metricEvent.getValue();

      NameBuilder nameBuilder = new NameBuilder(config.getSeparator(), config.getReplacementChar());
      if (config.getIncludeHost()) {
        nameBuilder.add(host);
      }
      nameBuilder.add(service);
      nameBuilder.add(metric);

      StatsDMetric.Type metricType = converter.addFilteredUserDims(service, metric, userDims, nameBuilder);

      if (metricType != null) {

        String fullName = nameBuilder.build();

        switch (metricType) {
          case count:
            statsd.count(fullName, value.longValue());
            break;
          case timer:
            statsd.time(fullName, value.longValue());
            break;
          case gauge:
            statsd.gauge(fullName, value.longValue());
            break;
        }
      } else {
        log.error("Metric=[%s] has no StatsD type mapping", metric);
      }
    }
  }

  @Override
  public void flush() throws IOException {}

  @Override
  public void close() throws IOException
  {
    statsd.stop();
  }

}
