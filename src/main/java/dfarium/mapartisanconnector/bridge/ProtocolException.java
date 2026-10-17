package dfarium.mapartisanconnector.bridge;

/** A message the bridge refuses, with the code sent back in `rejected` (spec section 8). */
public final class ProtocolException extends Exception {
    private final String code;

    public ProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
