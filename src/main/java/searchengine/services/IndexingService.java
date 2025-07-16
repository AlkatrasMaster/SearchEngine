package searchengine.services;

import java.net.MalformedURLException;
import java.util.Map;

public interface IndexingService {

    boolean isIndexingRunning();
    void startIndexing() throws InterruptedException;
    Map<String, Object> stopIndexing();
    void indexPage(String url) throws Exception;
}
