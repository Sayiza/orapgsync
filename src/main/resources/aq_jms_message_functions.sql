-- PostgreSQL helper functions for Oracle AQ$_JMS_TEXT_MESSAGE JSONB queries
-- These functions provide convenient access to JMS message data stored as JSONB

-- Extract text content from AQ JMS message
CREATE OR REPLACE FUNCTION aq_jms_get_text_content(message_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN message_data->>'text_content';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract JMS message ID
CREATE OR REPLACE FUNCTION aq_jms_get_message_id(message_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN message_data->'headers'->>'jms_message_id';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract JMS timestamp as bigint (epoch milliseconds)
CREATE OR REPLACE FUNCTION aq_jms_get_timestamp(message_data JSONB)
RETURNS BIGINT AS $$
BEGIN
    RETURN (message_data->'headers'->>'jms_timestamp')::BIGINT;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract JMS timestamp as PostgreSQL timestamp
CREATE OR REPLACE FUNCTION aq_jms_get_timestamp_pg(message_data JSONB)
RETURNS TIMESTAMP AS $$
BEGIN
    RETURN to_timestamp((message_data->'headers'->>'jms_timestamp')::BIGINT / 1000.0);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract JMS correlation ID
CREATE OR REPLACE FUNCTION aq_jms_get_correlation_id(message_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN message_data->'headers'->>'jms_correlation_id';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract JMS delivery mode
CREATE OR REPLACE FUNCTION aq_jms_get_delivery_mode(message_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN message_data->'headers'->>'jms_delivery_mode';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract custom property value
CREATE OR REPLACE FUNCTION aq_jms_get_property(message_data JSONB, property_name TEXT)
RETURNS TEXT AS $$
BEGIN
    RETURN message_data->'properties'->>property_name;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if message has a specific property
CREATE OR REPLACE FUNCTION aq_jms_has_property(message_data JSONB, property_name TEXT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN message_data->'properties' ? property_name;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get all property names as array
CREATE OR REPLACE FUNCTION aq_jms_get_property_names(message_data JSONB)
RETURNS TEXT[] AS $$
BEGIN
    RETURN ARRAY(SELECT jsonb_object_keys(message_data->'properties'));
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract message type
CREATE OR REPLACE FUNCTION aq_jms_get_message_type(message_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN message_data->>'message_type';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if message is a valid AQ JMS text message
CREATE OR REPLACE FUNCTION aq_jms_is_valid_message(message_data JSONB)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN message_data IS NOT NULL 
           AND message_data ? 'message_type'
           AND message_data->>'message_type' = 'JMS_TEXT_MESSAGE'
           AND message_data ? 'text_content';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get message size (length of text content)
CREATE OR REPLACE FUNCTION aq_jms_get_message_size(message_data JSONB)
RETURNS INTEGER AS $$
BEGIN
    RETURN length(message_data->>'text_content');
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Search for text content containing a pattern
CREATE OR REPLACE FUNCTION aq_jms_text_contains(message_data JSONB, search_text TEXT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN message_data->>'text_content' ILIKE '%' || search_text || '%';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract metadata information
CREATE OR REPLACE FUNCTION aq_jms_get_metadata(message_data JSONB)
RETURNS JSONB AS $$
BEGIN
    RETURN message_data->'metadata';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get conversion timestamp
CREATE OR REPLACE FUNCTION aq_jms_get_conversion_timestamp(message_data JSONB)
RETURNS TIMESTAMP AS $$
BEGIN
    RETURN (message_data->'metadata'->>'conversion_timestamp')::TIMESTAMP;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Example usage queries (commented out):
/*
-- Find all messages containing specific text
SELECT * FROM your_table 
WHERE aq_jms_text_contains(your_aq_column, 'error');

-- Get message details
SELECT 
    aq_jms_get_message_id(your_aq_column) as message_id,
    aq_jms_get_timestamp_pg(your_aq_column) as message_time,
    aq_jms_get_text_content(your_aq_column) as content,
    aq_jms_get_message_size(your_aq_column) as content_size
FROM your_table
WHERE aq_jms_is_valid_message(your_aq_column);

-- Find messages by correlation ID
SELECT * FROM your_table 
WHERE aq_jms_get_correlation_id(your_aq_column) = 'CORR123';

-- Get messages with custom properties
SELECT * FROM your_table 
WHERE aq_jms_has_property(your_aq_column, 'priority')
  AND aq_jms_get_property(your_aq_column, 'priority')::INTEGER > 5;

-- Create index for better performance on message searches
CREATE INDEX idx_aq_message_text 
ON your_table USING GIN ((aq_jms_get_text_content(your_aq_column)) gin_trgm_ops);

CREATE INDEX idx_aq_message_id 
ON your_table ((aq_jms_get_message_id(your_aq_column)));

CREATE INDEX idx_aq_message_timestamp 
ON your_table ((aq_jms_get_timestamp_pg(your_aq_column)));
*/