#!/bin/bash
set -e

# Initialize JOTP PostgreSQL databases

# Create main databases
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create additional databases
    CREATE DATABASE jotp_saga;
    CREATE DATABASE jotp_events;
    CREATE DATABASE jotp_state;

    -- Grant permissions
    GRANT ALL PRIVILEGES ON DATABASE jotp_saga TO $POSTGRES_USER;
    GRANT ALL PRIVILEGES ON DATABASE jotp_events TO $POSTGRES_USER;
    GRANT ALL PRIVILEGES ON DATABASE jotp_state TO $POSTGRES_USER;
EOSQL

# Initialize saga database schema
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "jotp_saga" <<-EOSQL
    -- Saga state table
    CREATE TABLE IF NOT EXISTS saga_state (
        id SERIAL PRIMARY KEY,
        saga_id VARCHAR(255) UNIQUE NOT NULL,
        saga_type VARCHAR(100) NOT NULL,
        current_state VARCHAR(100) NOT NULL,
        state_data JSONB,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        version INTEGER DEFAULT 0
    );

    CREATE INDEX idx_saga_id ON saga_state(saga_id);
    CREATE INDEX idx_saga_type ON saga_state(saga_type);
    CREATE INDEX idx_saga_state ON saga_state(current_state);

    -- Saga log table
    CREATE TABLE IF NOT EXISTS saga_log (
        id SERIAL PRIMARY KEY,
        saga_id VARCHAR(255) NOT NULL,
        saga_type VARCHAR(100) NOT NULL,
        event_type VARCHAR(100) NOT NULL,
        event_data JSONB,
        timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

    CREATE INDEX idx_saga_log_saga_id ON saga_log(saga_id);
    CREATE INDEX idx_saga_log_timestamp ON saga_log(timestamp);
EOSQL

# Initialize events database schema
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "jotp_events" <<-EOSQL
    -- Event store table
    CREATE TABLE IF NOT EXISTS event_store (
        id BIGSERIAL PRIMARY KEY,
        aggregate_id VARCHAR(255) NOT NULL,
        aggregate_type VARCHAR(100) NOT NULL,
        event_type VARCHAR(100) NOT NULL,
        event_data JSONB NOT NULL,
        version INTEGER NOT NULL,
        timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        causation_id VARCHAR(255),
        correlation_id VARCHAR(255)
    );

    CREATE INDEX idx_event_aggregate_id ON event_store(aggregate_id);
    CREATE INDEX idx_event_aggregate_type ON event_store(aggregate_type);
    CREATE INDEX idx_event_type ON event_store(event_type);
    CREATE INDEX idx_event_timestamp ON event_store(timestamp);
    CREATE INDEX idx_event_correlation ON event_store(correlation_id);

    -- Create unique constraint for optimistic locking
    CREATE UNIQUE INDEX idx_event_aggregate_version ON event_store(aggregate_id, version);
EOSQL

# Initialize state database schema
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "jotp_state" <<-EOSQL
    -- Process registry table
    CREATE TABLE IF NOT EXISTS process_registry (
        id BIGSERIAL PRIMARY KEY,
        process_id VARCHAR(255) UNIQUE NOT NULL,
        node_id VARCHAR(100) NOT NULL,
        process_type VARCHAR(100) NOT NULL,
        state JSONB,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        last_heartbeat TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

    CREATE INDEX idx_process_id ON process_registry(process_id);
    CREATE INDEX idx_node_id ON process_registry(node_id);
    CREATE INDEX idx_process_type ON process_registry(process_type);
    CREATE INDEX idx_process_heartbeat ON process_registry(last_heartbeat);

    -- Distributed lock table
    CREATE TABLE IF NOT EXISTS distributed_lock (
        lock_name VARCHAR(255) PRIMARY KEY,
        locked_by VARCHAR(255) NOT NULL,
        locked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        expires_at TIMESTAMP NOT NULL
    );

    CREATE INDEX idx_lock_expires ON distributed_lock(expires_at);
EOSQL

echo "PostgreSQL databases initialized successfully"
