package io.gravitee.policy.ratelimit;

import io.gravitee.common.http.GraviteeHttpHeader;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.policy.PolicyChain;
import io.gravitee.gateway.api.policy.PolicyResult;
import io.gravitee.gateway.api.policy.annotations.OnRequest;
import io.gravitee.policy.ratelimit.configuration.RateLimitPolicyConfiguration;
import io.gravitee.policy.ratelimit.provider.RateLimitProviderFactory;
import io.gravitee.policy.ratelimit.provider.RateLimitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rate limit policy, also known as throttling insure that a user (given its api key or IP address) is allowed
 * to make x requests per y time period.
 *
 * Useful when you want to ensure that your APIs does not get flooded with requests.
 *
 * @author David BRASSELY (brasseld at gmail.com)
 */
@SuppressWarnings("unused")
public class RateLimitPolicy  {

    /**
     * LOGGER
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitPolicy.class);

    /**
     * The maximum number of requests that the consumer is permitted to make per time unit.
     */
    private static final String X_RATE_LIMIT_LIMIT = "X-Rate-Limit-Limit";

    /**
     * The number of requests remaining in the current rate limit window.
     */
    private static final String X_RATE_LIMIT_REMAINING = "X-Rate-Limit-Remaining";

    /**
     * The time at which the current rate limit window resets in UTC epoch seconds.
     */
    private static final String X_RATE_LIMIT_RESET = "X-Rate-Limit-Reset";

    /**
     * Rate limit policy configuration
     */
    private final RateLimitPolicyConfiguration rateLimitPolicyConfiguration;

    public RateLimitPolicy(RateLimitPolicyConfiguration rateLimitPolicyConfiguration) {
        this.rateLimitPolicyConfiguration = rateLimitPolicyConfiguration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, PolicyChain policyChain) {
        String storageKey = createStorageKey(request);

        RateLimitResult rateLimitResult = RateLimitProviderFactory.getRateLimitProvider().acquire(
                storageKey,
                rateLimitPolicyConfiguration.getLimit(),
                rateLimitPolicyConfiguration.getPeriodTime(),
                rateLimitPolicyConfiguration.getPeriodTimeUnit()
        );

        if (rateLimitResult.isExceeded()) {
            response.headers().set(X_RATE_LIMIT_LIMIT, Long.toString(rateLimitPolicyConfiguration.getLimit()));
            response.headers().set(X_RATE_LIMIT_REMAINING, Long.toString(rateLimitResult.getRemains()));
            response.headers().set(X_RATE_LIMIT_RESET, Long.toString(rateLimitResult.getRemains()));
            policyChain.failWith(createLimitExceeded());
        } else {
            policyChain.doNext(request, response);
        }
    }

    private String createStorageKey(Request request) {
        StringBuilder builder = new StringBuilder();

        String userId = request.headers().getFirst(GraviteeHttpHeader.X_GRAVITEE_API_KEY);
        if (userId == null || userId.isEmpty()) {
            // Use the remote (client) IP if no API Key has been specified in HTTP headers
            userId = request.remoteAddress();
        }

        builder.append(userId);
        builder.append(';');
        builder.append(request.headers().getFirst(GraviteeHttpHeader.X_GRAVITEE_API_NAME));

        return builder.toString();
    }

    private PolicyResult createLimitExceeded() {
        return new PolicyResult() {
            @Override
            public boolean isFailure() {
                return true;
            }

            @Override
            public int httpStatusCode() {
                return HttpStatusCode.TOO_MANY_REQUESTS_429;
            }

            @Override
            public String message() {
                return "Rate limit exceeded";
            }
        };
    }
}
