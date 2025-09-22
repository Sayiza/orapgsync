-- PostgreSQL helper functions for Oracle AQ$_RECIPIENTS JSONB queries
-- These functions provide convenient access to AQ recipients data stored as JSONB

-- Extract all recipient addresses as array
CREATE OR REPLACE FUNCTION aq_recipients_get_addresses(recipients_data JSONB)
RETURNS TEXT[] AS $$
BEGIN
    RETURN ARRAY(
        SELECT jsonb_array_elements(recipients_data->'recipients')->>'address'
    );
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Count total number of recipients
CREATE OR REPLACE FUNCTION aq_recipients_count(recipients_data JSONB)
RETURNS INTEGER AS $$
BEGIN
    RETURN (recipients_data->'metadata'->>'total_count')::INTEGER;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Count recipients by delivery status
CREATE OR REPLACE FUNCTION aq_recipients_count_by_status(recipients_data JSONB, status TEXT)
RETURNS INTEGER AS $$
BEGIN
    RETURN (
        SELECT COUNT(*)
        FROM jsonb_array_elements(recipients_data->'recipients') AS recipient
        WHERE recipient->>'delivery_status' = UPPER(status)
    )::INTEGER;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get recipients by delivery status
CREATE OR REPLACE FUNCTION aq_recipients_get_by_status(recipients_data JSONB, status TEXT)
RETURNS JSONB AS $$
BEGIN
    RETURN jsonb_agg(recipient)
    FROM jsonb_array_elements(recipients_data->'recipients') AS recipient
    WHERE recipient->>'delivery_status' = UPPER(status);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if any recipients have pending delivery
CREATE OR REPLACE FUNCTION aq_recipients_has_pending(recipients_data JSONB)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1
        FROM jsonb_array_elements(recipients_data->'recipients') AS recipient
        WHERE recipient->>'delivery_status' = 'PENDING'
    );
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if all recipients have been delivered
CREATE OR REPLACE FUNCTION aq_recipients_all_delivered(recipients_data JSONB)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN NOT EXISTS (
        SELECT 1
        FROM jsonb_array_elements(recipients_data->'recipients') AS recipient
        WHERE recipient->>'delivery_status' != 'DELIVERED'
    );
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get specific recipient by address
CREATE OR REPLACE FUNCTION aq_recipients_get_by_address(recipients_data JSONB, search_address TEXT)
RETURNS JSONB AS $$
BEGIN
    RETURN (
        SELECT recipient
        FROM jsonb_array_elements(recipients_data->'recipients') AS recipient
        WHERE recipient->>'address' ILIKE '%' || search_address || '%'
        LIMIT 1
    );
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get recipients by name pattern
CREATE OR REPLACE FUNCTION aq_recipients_get_by_name(recipients_data JSONB, search_name TEXT)
RETURNS JSONB AS $$
BEGIN
    RETURN jsonb_agg(recipient)
    FROM jsonb_array_elements(recipients_data->'recipients') AS recipient
    WHERE recipient->>'name' ILIKE '%' || search_name || '%';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract delivery mode from metadata
CREATE OR REPLACE FUNCTION aq_recipients_get_delivery_mode(recipients_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN recipients_data->'metadata'->>'delivery_mode';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract priority level from metadata
CREATE OR REPLACE FUNCTION aq_recipients_get_priority_level(recipients_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN recipients_data->'metadata'->>'priority_level';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get timestamp as PostgreSQL timestamp
CREATE OR REPLACE FUNCTION aq_recipients_get_timestamp_pg(recipients_data JSONB)
RETURNS TIMESTAMP AS $$
BEGIN
    RETURN to_timestamp((recipients_data->'metadata'->>'timestamp')::BIGINT / 1000.0);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get timestamp as bigint (epoch milliseconds)
CREATE OR REPLACE FUNCTION aq_recipients_get_timestamp(recipients_data JSONB)
RETURNS BIGINT AS $$
BEGIN
    RETURN (recipients_data->'metadata'->>'timestamp')::BIGINT;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract recipients type
CREATE OR REPLACE FUNCTION aq_recipients_get_type(recipients_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN recipients_data->>'recipients_type';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if recipients data is valid
CREATE OR REPLACE FUNCTION aq_recipients_is_valid(recipients_data JSONB)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN recipients_data IS NOT NULL 
           AND recipients_data ? 'recipients_type'
           AND recipients_data->>'recipients_type' = 'AQ_RECIPIENTS'
           AND recipients_data ? 'recipients'
           AND jsonb_array_length(recipients_data->'recipients') > 0;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get all recipient names as array
CREATE OR REPLACE FUNCTION aq_recipients_get_names(recipients_data JSONB)
RETURNS TEXT[] AS $$
BEGIN
    RETURN ARRAY(
        SELECT jsonb_array_elements(recipients_data->'recipients')->>'name'
    );
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get recipients with specific routing info
CREATE OR REPLACE FUNCTION aq_recipients_get_by_routing(recipients_data JSONB, routing_info TEXT)
RETURNS JSONB AS $$
BEGIN
    RETURN jsonb_agg(recipient)
    FROM jsonb_array_elements(recipients_data->'recipients') AS recipient
    WHERE recipient->>'routing_info' = UPPER(routing_info);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if recipients list contains specific address
CREATE OR REPLACE FUNCTION aq_recipients_contains_address(recipients_data JSONB, search_address TEXT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1
        FROM jsonb_array_elements(recipients_data->'recipients') AS recipient
        WHERE recipient->>'address' ILIKE '%' || search_address || '%'
    );
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get recipients with highest priority
CREATE OR REPLACE FUNCTION aq_recipients_get_high_priority(recipients_data JSONB)
RETURNS JSONB AS $$
DECLARE
    max_priority INTEGER;
BEGIN
    -- Find maximum priority value
    SELECT MAX((recipient->>'priority')::INTEGER)
    INTO max_priority
    FROM jsonb_array_elements(recipients_data->'recipients') AS recipient
    WHERE recipient ? 'priority';
    
    -- Return recipients with maximum priority
    RETURN jsonb_agg(recipient)
    FROM jsonb_array_elements(recipients_data->'recipients') AS recipient
    WHERE (recipient->>'priority')::INTEGER = max_priority;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get first recipient (useful for single-recipient cases)
CREATE OR REPLACE FUNCTION aq_recipients_get_first(recipients_data JSONB)
RETURNS JSONB AS $$
BEGIN
    RETURN recipients_data->'recipients'->0;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get first recipient's address
CREATE OR REPLACE FUNCTION aq_recipients_get_first_address(recipients_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN recipients_data->'recipients'->0->>'address';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if delivery mode is broadcast
CREATE OR REPLACE FUNCTION aq_recipients_is_broadcast(recipients_data JSONB)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN UPPER(recipients_data->'metadata'->>'delivery_mode') = 'BROADCAST';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get all metadata as JSONB
CREATE OR REPLACE FUNCTION aq_recipients_get_metadata(recipients_data JSONB)
RETURNS JSONB AS $$
BEGIN
    RETURN recipients_data->'metadata';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract technical information
CREATE OR REPLACE FUNCTION aq_recipients_get_technical_info(recipients_data JSONB)
RETURNS JSONB AS $$
BEGIN
    RETURN recipients_data->'technical_info';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get conversion timestamp
CREATE OR REPLACE FUNCTION aq_recipients_get_conversion_timestamp(recipients_data JSONB)
RETURNS TIMESTAMP AS $$
BEGIN
    RETURN (recipients_data->'technical_info'->>'conversion_timestamp')::TIMESTAMP;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get original Oracle type
CREATE OR REPLACE FUNCTION aq_recipients_get_original_type(recipients_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN recipients_data->'technical_info'->>'original_type';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get recipients by domain (email domain)
CREATE OR REPLACE FUNCTION aq_recipients_get_by_domain(recipients_data JSONB, domain TEXT)
RETURNS JSONB AS $$
BEGIN
    RETURN jsonb_agg(recipient)
    FROM jsonb_array_elements(recipients_data->'recipients') AS recipient
    WHERE recipient->>'address' ILIKE '%@' || domain || '%';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Count recipients by domain
CREATE OR REPLACE FUNCTION aq_recipients_count_by_domain(recipients_data JSONB, domain TEXT)
RETURNS INTEGER AS $$
BEGIN
    RETURN (
        SELECT COUNT(*)
        FROM jsonb_array_elements(recipients_data->'recipients') AS recipient
        WHERE recipient->>'address' ILIKE '%@' || domain || '%'
    )::INTEGER;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get delivery statistics summary
CREATE OR REPLACE FUNCTION aq_recipients_get_delivery_stats(recipients_data JSONB)
RETURNS JSONB AS $$
DECLARE
    stats JSONB;
BEGIN
    SELECT jsonb_build_object(
        'total_recipients', aq_recipients_count(recipients_data),
        'pending', aq_recipients_count_by_status(recipients_data, 'PENDING'),
        'delivered', aq_recipients_count_by_status(recipients_data, 'DELIVERED'),
        'failed', aq_recipients_count_by_status(recipients_data, 'FAILED'),
        'delivery_mode', aq_recipients_get_delivery_mode(recipients_data),
        'all_delivered', aq_recipients_all_delivered(recipients_data),
        'has_pending', aq_recipients_has_pending(recipients_data)
    ) INTO stats;
    
    RETURN stats;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Example usage queries (commented out):
/*
-- Find all recipients with pending deliveries
SELECT * FROM message_table 
WHERE aq_recipients_has_pending(recipients_column);

-- Get message details with recipient counts
SELECT 
    message_id,
    aq_recipients_count(recipients_column) as total_recipients,
    aq_recipients_count_by_status(recipients_column, 'DELIVERED') as delivered_count,
    aq_recipients_get_delivery_mode(recipients_column) as delivery_mode
FROM message_table
WHERE aq_recipients_is_valid(recipients_column);

-- Find messages sent to specific domain
SELECT * FROM message_table 
WHERE aq_recipients_count_by_domain(recipients_column, 'company.com') > 0;

-- Get all addresses for a specific message
SELECT aq_recipients_get_addresses(recipients_column) as addresses
FROM message_table 
WHERE message_id = 123;

-- Find broadcast messages with pending recipients
SELECT * FROM message_table 
WHERE aq_recipients_is_broadcast(recipients_column)
  AND aq_recipients_has_pending(recipients_column);

-- Get delivery statistics for all messages
SELECT 
    message_id,
    aq_recipients_get_delivery_stats(recipients_column) as stats
FROM message_table;

-- Find messages by recipient name pattern
SELECT * FROM message_table 
WHERE aq_recipients_get_by_name(recipients_column, 'John') IS NOT NULL;

-- Create indexes for better performance on recipient queries
CREATE INDEX idx_recipients_addresses 
ON message_table USING GIN ((aq_recipients_get_addresses(recipients_column)));

CREATE INDEX idx_recipients_delivery_mode 
ON message_table ((aq_recipients_get_delivery_mode(recipients_column)));

CREATE INDEX idx_recipients_count 
ON message_table ((aq_recipients_count(recipients_column)));

CREATE INDEX idx_recipients_has_pending 
ON message_table ((aq_recipients_has_pending(recipients_column))) 
WHERE aq_recipients_has_pending(recipients_column) = true;

-- GIN index for complex JSONB queries
CREATE INDEX idx_recipients_jsonb 
ON message_table USING GIN (recipients_column);
*/