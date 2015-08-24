# solr-log4j2

This project provides a Solr LogWatcher implementation that works with the Log4J 2 API.

To use this project, you need to build it using (`mvn clean package`) and then copy the resulting JAR file
to the lib directory of your Solr home, such as `solr-5.2.1/server/solr/lib`. In addition, you need to
update the solr.xml file to use this implementation by adding the following XML snippet:

```
  <logging>
    <str name="enabled">true</str>
    <str name="class">org.apache.solr.logging.log4j2.Log4j2Watcher</str>
    <watcher>
      <int name="size">100</int>
      <str name="threshold">INFO</str>
    </watcher>
  </logging>
```
