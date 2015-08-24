package org.apache.solr.logging.log4j2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.logging.ListenerConfig;
import org.apache.solr.logging.LoggerInfo;
import org.junit.Test;

import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class Log4j2WatcherTest {
  @Test
  public void testLog4j2WatcherImpl() throws Exception {
    Log4j2Watcher watcher = new Log4j2Watcher();

    assertEquals("Log4j2", watcher.getName());

    watcher.registerListener(new ListenerConfig());

    assertEquals("Expected initial threshold to be WARN", "WARN", watcher.getThreshold());

    String newThreshold = Level.INFO.toString();
    watcher.setThreshold(newThreshold);
    assertEquals("Expected threshold to be " + newThreshold + " after update", newThreshold, watcher.getThreshold());

    Collection<LoggerInfo> loggers = watcher.getAllLoggers();
    assertNotNull(loggers);
    assertTrue(!loggers.isEmpty());

    Random rand = new Random(5150);
    List<String> allLevels = watcher.getAllLevels();
    for (LoggerInfo info : loggers) {
      String randomLevel = allLevels.get(rand.nextInt(allLevels.size()));
      watcher.setLogLevel(info.getName(), randomLevel);
      assertEquals(randomLevel, LogManager.getLogger(info.getName()).getLevel().toString());
    }

    // so our history only has 1 doc in it
    long since = System.currentTimeMillis();
    Thread.sleep(1000);

    newThreshold = Level.WARN.toString();
    watcher.setThreshold(newThreshold);
    assertEquals("Expected threshold to be " + newThreshold + " after update", newThreshold, watcher.getThreshold());

    Logger solrLog = LogManager.getLogger("org.apache.solr");
    watcher.setLogLevel(solrLog.getName(), Level.WARN.toString());
    assertEquals(Level.WARN.toString(), String.valueOf(solrLog.getLevel()));
    String warnMsg = "This is a warn message.";
    solrLog.warn(warnMsg);

    SolrDocumentList history = watcher.getHistory(since, new AtomicBoolean());
    assertTrue(history.getNumFound() >= 1);

    int foundMsg = 0;
    for (SolrDocument next : history) {
      if (solrLog.getName().equals(next.getFirstValue("logger"))) {
        ++foundMsg;
        
        assertNotNull(next);
        assertNotNull(next.getFirstValue("time"));
        assertEquals(warnMsg, next.getFirstValue("message"));
      }
    }

    if (foundMsg != 1)
      fail("Test warn message not captured by the LogWatcher as it should have been; history="+history);
  }
}
