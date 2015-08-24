package org.apache.solr.logging.log4j2;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.ThresholdFilter;
import org.apache.logging.log4j.message.Message;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.logging.CircularList;
import org.apache.solr.logging.ListenerConfig;
import org.apache.solr.logging.LogWatcher;
import org.apache.solr.logging.LoggerInfo;

import com.google.common.base.Throwables;

public class Log4j2Watcher extends LogWatcher<LogEvent> {

  protected class Log4j2Appender extends AbstractAppender {

    private Log4j2Watcher watcher;
    private ThresholdFilter filter;
    private Level threshold;

    Log4j2Appender(Log4j2Watcher watcher, ThresholdFilter filter, Level threshold) {
      super("Log4j2WatcherAppender", filter, null);
      this.watcher = watcher;
      this.filter = filter;
      this.threshold = threshold;
    }

    public void append(LogEvent logEvent) {
      watcher.add(logEvent, logEvent.getTimeMillis());
    }

    public Level getThreshold() {
      return threshold;
    }

    public void setThreshold(Level threshold) {
      this.threshold = threshold;
      removeFilter(filter);
      filter = ThresholdFilter.createFilter(threshold, Filter.Result.ACCEPT, Filter.Result.DENY);
      addFilter(filter);
    }
  }

  protected class Log4j2Info extends LoggerInfo {
    final Logger logger;

    Log4j2Info(String name, Logger logger) {
      super(name);
      this.logger = logger;
    }

    @Override
    public String getLevel() {
      Object level = (logger != null) ? logger.getLevel() : null;
      return (level != null) ? level.toString() : null;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public boolean isSet() {
      return (logger != null && logger.getLevel() != null);
    }
  }

  public static final Logger watcherLog = LogManager.getLogger(Log4j2Watcher.class);

  protected Log4j2Appender appender = null;

  @Override
  public String getName() {
    return "Log4j2";
  }

  @Override
  public List<String> getAllLevels() {
    return Arrays.asList(
      Level.ALL.toString(),
      Level.TRACE.toString(),
      Level.DEBUG.toString(),
      Level.INFO.toString(),
      Level.WARN.toString(),
      Level.ERROR.toString(),
      Level.FATAL.toString(),
      Level.OFF.toString());
  }

  @Override
  public void setLogLevel(String category, String level) {
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    LoggerConfig loggerConfig = getLoggerConfig(ctx, category);
    if (loggerConfig != null) {
      boolean madeChanges = false;
      if (level == null || "unset".equals(level) || "null".equals(level)) {
        level = Level.OFF.toString();
        loggerConfig.setLevel(Level.OFF);
        madeChanges = true;
      } else {
        try {
          loggerConfig.setLevel(Level.valueOf(level));
          madeChanges = true;
        } catch (IllegalArgumentException iae) {
          watcherLog.error(level+" is not a valid log level! Valid values are: "+getAllLevels());
        }
      }
      if (madeChanges) {
        ctx.updateLoggers();
        watcherLog.info("Set log level to '" + level + "' for category: " + category);
      }
    } else {
      watcherLog.warn("Cannot set level to '" + level + "' for category: " + category + "; no LoggerConfig found!");
    }
  }

  protected boolean isRootLogger(String category) {
    return LoggerInfo.ROOT_NAME.equals(category);
  }

  protected LoggerConfig getLoggerConfig(LoggerContext ctx, String category) {
    Configuration config = ctx.getConfiguration();
    return isRootLogger(category) ? config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME)
                                  : config.getLoggerConfig(category);
  }

  @Override
  public Collection<LoggerInfo> getAllLoggers() {
    Logger root = LogManager.getRootLogger();
    Map<String,LoggerInfo> map = new HashMap<String,LoggerInfo>();

    LoggerContext ctx = (LoggerContext)LogManager.getContext(false);
    for (org.apache.logging.log4j.core.Logger logger : ctx.getLoggers()) {
      String name = logger.getName();
      if (logger == root || root.equals(logger) || isRootLogger(name))
        continue;

      map.put(name, new Log4j2Info(name, logger));

      while (true) {
        int dot = name.lastIndexOf(".");
        if (dot < 0)
          break;

        name = name.substring(0, dot);
        if (!map.containsKey(name))
          map.put(name, new Log4j2Info(name, null));
      }
    }

    map.put(LoggerInfo.ROOT_NAME, new Log4j2Info(LoggerInfo.ROOT_NAME, root));

    return map.values();
  }

  @Override
  public void setThreshold(String level) {
    Log4j2Appender app = getAppender();
    Level current = app.getThreshold();
    app.setThreshold(Level.toLevel(level));
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    LoggerConfig config = getLoggerConfig(ctx, LoggerInfo.ROOT_NAME);
    config.removeAppender(app.getName());
    config.addAppender(app, app.getThreshold(), app.getFilter());
    ((LoggerContext)LogManager.getContext(false)).updateLoggers();
    watcherLog.info("Updated watcher threshold from "+current+" to " + level);
  }

  @Override
  public String getThreshold() {
    return String.valueOf(getAppender().getThreshold());
  }

  protected Log4j2Appender getAppender() {
    if (appender == null)
      throw new IllegalStateException("No appenders configured! Must call registerListener(ListenerConfig) first.");
    return appender;
  }

  @Override
  public void registerListener(ListenerConfig cfg) {
    if (history != null)
      throw new IllegalStateException("History already registered");

    history = new CircularList<LogEvent>(cfg.size);

    Level threshold = (cfg.threshold != null) ? Level.toLevel(cfg.threshold) : Level.WARN;
    ThresholdFilter filter = ThresholdFilter.createFilter(threshold, Filter.Result.ACCEPT, Filter.Result.DENY);
    appender = new Log4j2Appender(this, filter, threshold);
    if (!appender.isStarted())
      appender.start();

    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    LoggerConfig config = getLoggerConfig(ctx, LoggerInfo.ROOT_NAME);
    config.addAppender(appender, threshold, filter);
    ctx.updateLoggers();
  }

  @Override
  public long getTimestamp(LogEvent event) {
    return event.getTimeMillis();
  }

  @Override
  public SolrDocument toSolrDocument(LogEvent event) {
    SolrDocument doc = new SolrDocument();
    doc.setField("time", new Date(event.getTimeMillis()));
    doc.setField("level", event.getLevel().toString());
    doc.setField("logger", event.getLoggerName());
    Message message = event.getMessage();
    doc.setField("message", message.getFormattedMessage());
    Throwable t = message.getThrowable();
    if (t != null)
      doc.setField("trace", Throwables.getStackTraceAsString(t));

    Map<String,String> contextMap = event.getContextMap();
    if (contextMap != null) {
      for (String key : contextMap.keySet())
        doc.setField(key, contextMap.get(key));
    }

    if (!doc.containsKey("core"))
      doc.setField("core", ""); // avoids an ugly "undefined" column in the UI

    return doc;
  }
}