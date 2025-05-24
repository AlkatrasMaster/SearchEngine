package searchengine.exceptions;

public class IndexingAlreadyRunningException extends RuntimeException {
    public IndexingAlreadyRunningException(String message) {
        super(message);
    }
}
