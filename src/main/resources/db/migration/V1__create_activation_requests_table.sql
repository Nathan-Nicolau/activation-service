CREATE TABLE activation_requests (
    id UUID NOT NULL,
    external_id VARCHAR(100) NOT NULL,
    customer_id VARCHAR(100) NOT NULL,
    product_code VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    error_message VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_activation_requests PRIMARY KEY (id),
    CONSTRAINT uk_activation_requests_external_id UNIQUE (external_id)
);

CREATE INDEX idx_activation_requests_status_created_at
    ON activation_requests (status, created_at ASC);

CREATE INDEX idx_activation_requests_customer_status
    ON activation_requests (customer_id, status);
