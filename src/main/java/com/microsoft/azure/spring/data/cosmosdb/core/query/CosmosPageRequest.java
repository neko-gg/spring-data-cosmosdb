/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */
package com.microsoft.azure.spring.data.cosmosdb.core.query;

import com.azure.data.cosmos.FeedResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * CosmosPageRequest representing page request during pagination query, field
 * {@link FeedResponse#continuationToken()}  response continuation token} is saved
 * to help query next page.
 * <p>
 * The requestContinuation token should be saved after each request and reused in later queries.
 */
public class CosmosPageRequest extends PageRequest {
    private static final long serialVersionUID = 6093304300037688375L;

    // Request continuation token used to resume query
    private String requestContinuation;

    public CosmosPageRequest(int page, int size, String requestContinuation) {
        super(page, size, Sort.unsorted());
        this.requestContinuation = requestContinuation;
    }

    public static CosmosPageRequest of(int page, int size, String requestContinuation) {
        return new CosmosPageRequest(page, size, requestContinuation);
    }

    public CosmosPageRequest(int page, int size, String requestContinuation, Sort sort) {
        super(page, size, sort);
        this.requestContinuation = requestContinuation;
    }

    public static CosmosPageRequest of(int page, int size, String requestContinuation, Sort sort) {
        return new CosmosPageRequest(page, size, requestContinuation, sort);
    }

    public String getRequestContinuation() {
        return this.requestContinuation;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();

        result = 31 * result + (requestContinuation != null ? requestContinuation.hashCode() : 0);

        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof CosmosPageRequest)) {
            return false;
        }

        final CosmosPageRequest that = (CosmosPageRequest) obj;

        final boolean continuationTokenEquals = requestContinuation != null ?
                requestContinuation.equals(that.requestContinuation) : that.requestContinuation == null;

        return continuationTokenEquals && super.equals(that);
    }
}
