package com.ultraviolette.fotaservice;

import android.os.UpdateEngine;

import java.io.Serializable;
import java.util.List;

/**
 * Payload that will be given to {@link UpdateEngine#applyPayload)}.
 */
public class PayloadSpec implements Serializable {

    private static final long serialVersionUID = 41043L;

    /**
     * Creates a payload spec {@link Builder}
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    private String mUrl;
    private long mOffset;
    private long mSize;
    private List<String> mProperties;

    public PayloadSpec(Builder b) {
        this.mUrl = b.mUrl;
        this.mOffset = b.mOffset;
        this.mSize = b.mSize;
        this.mProperties = b.mProperties;
    }

    public String getUrl() {
        return mUrl;
    }

    public long getOffset() {
        return mOffset;
    }

    public long getSize() {
        return mSize;
    }

    public List<String> getProperties() {
        return mProperties;
    }

    /**
     * payload spec builder.
     *
     * <p>Usage:</p>
     *
     * {@code
     *   PayloadSpec spec = PayloadSpec.newBuilder()
     *     .url("url")
     *     .build();
     * }
     */
    public static class Builder {
        private String mUrl;
        private long mOffset;
        private long mSize;
        private List<String> mProperties;

        public Builder() {
        }

        /**
         * set url
         */
        public Builder url(String url) {
            this.mUrl = url;
            return this;
        }

        /**
         * set offset
         */
        public Builder offset(long offset) {
            this.mOffset = offset;
            return this;
        }

        /**
         * set size
         */
        public Builder size(long size) {
            this.mSize = size;
            return this;
        }

        /**
         * set properties
         */
        public Builder properties(List<String> properties) {
            this.mProperties = properties;
            return this;
        }

        /**
         * build {@link PayloadSpec}
         */
        public PayloadSpec build() {
            return new PayloadSpec(this);
        }
    }
}
