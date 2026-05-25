package space.wlshow.scope.agent;

import java.util.List;

public class ParseException extends RuntimeException {
    private final List<String> lastErrors;

    public ParseException(String message, List<String> lastErrors) {
        super(message);
        this.lastErrors = lastErrors == null ? List.of() : List.copyOf(lastErrors);
    }

    public List<String> lastErrors() { return lastErrors; }
}