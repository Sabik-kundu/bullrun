package bullrun;

final class ApiError extends RuntimeException {
    final int code;

    ApiError(int code, String message) {
        super(message);
        this.code = code;
    }
}
