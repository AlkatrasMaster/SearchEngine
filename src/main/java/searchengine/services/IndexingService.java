package searchengine.services;

import java.util.Map;

public interface IndexingService {

    boolean isIndexingRunning();
    void startIndexing() throws InterruptedException;
    Map<String, Object> stopIndexing();
}
