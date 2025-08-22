package searchengine.exceptions;

public class IndexNotReadyException extends RuntimeException {
    public IndexNotReadyException(String message) {
        super(message);
    }
}
