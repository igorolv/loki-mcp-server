package ru.it_spectrum.ai.loki.mcp.client;

import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import ru.it_spectrum.ai.loki.mcp.model.ToolError;
import ru.it_spectrum.ai.loki.mcp.service.LokiOperationException;

final class TransportErrors {
    private TransportErrors() {}

    static LokiOperationException error(ErrorCode code) {
        String message = switch (code) {
            case INVALID_ARGUMENT -> "Invalid Loki request arguments.";
            case UPSTREAM_BAD_REQUEST -> "Loki rejected the request; check query and parameters.";
            case UPSTREAM_UNAUTHORIZED -> "Loki authentication failed; check connection credentials.";
            case UPSTREAM_FORBIDDEN -> "Loki access denied; check permissions and tenant.";
            case ENDPOINT_UNAVAILABLE -> "Endpoint is unavailable on this path; other Loki endpoints may still work.";
            case UPSTREAM_RATE_LIMITED -> "Loki rate limit reached; retry later.";
            case UPSTREAM_UNAVAILABLE -> "Loki is temporarily unavailable.";
            case UPSTREAM_HTTP_ERROR -> "Unexpected HTTP status from Loki; check the configured endpoint.";
            case UPSTREAM_TIMEOUT -> "Loki request timed out.";
            case UPSTREAM_CONNECTION_ERROR -> "Could not communicate with Loki; check connection settings and network.";
            case UPSTREAM_RESPONSE_TOO_LARGE -> "Loki response exceeds maxHttpResponseBytes; reduce the query scope.";
            case UPSTREAM_INVALID_RESPONSE -> "Loki returned an invalid or unsupported response.";
            case UPSTREAM_QUERY_ERROR -> "Loki reported a query error; check query and parameters.";
            case OPERATION_CANCELLED -> "Loki operation was cancelled.";
            default -> "Operation failed internally.";
        };
        boolean retryable = switch (code) {
            case UPSTREAM_RATE_LIMITED, UPSTREAM_UNAVAILABLE, UPSTREAM_TIMEOUT, UPSTREAM_CONNECTION_ERROR -> true;
            default -> false;
        };
        return new LokiOperationException(new ToolError(code, message, retryable));
    }

    static LokiOperationException http(int status) {
        return error(switch (status) {
            case 400 -> ErrorCode.UPSTREAM_BAD_REQUEST;
            case 401 -> ErrorCode.UPSTREAM_UNAUTHORIZED;
            case 403 -> ErrorCode.UPSTREAM_FORBIDDEN;
            case 404 -> ErrorCode.ENDPOINT_UNAVAILABLE;
            case 408, 504 -> ErrorCode.UPSTREAM_TIMEOUT;
            case 429 -> ErrorCode.UPSTREAM_RATE_LIMITED;
            default -> status >= 500 && status <= 599 ? ErrorCode.UPSTREAM_UNAVAILABLE : ErrorCode.UPSTREAM_HTTP_ERROR;
        });
    }
}
