-- PostgreSQL helper functions for Oracle AQ$_SIG_PROP JSONB queries
-- These functions provide convenient access to AQ signature property data stored as JSONB

-- Extract signature algorithm
CREATE OR REPLACE FUNCTION aq_sig_prop_get_algorithm(sig_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN sig_data->'signature_properties'->>'algorithm';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract signature digest
CREATE OR REPLACE FUNCTION aq_sig_prop_get_digest(sig_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN sig_data->'signature_properties'->>'digest';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract signature value
CREATE OR REPLACE FUNCTION aq_sig_prop_get_signature(sig_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN sig_data->'signature_properties'->>'signature';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract validation status
CREATE OR REPLACE FUNCTION aq_sig_prop_get_validation_status(sig_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN sig_data->'metadata'->>'validation_status';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract signer information
CREATE OR REPLACE FUNCTION aq_sig_prop_get_signer(sig_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN sig_data->'metadata'->>'signer';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract timestamp as bigint (epoch milliseconds)
CREATE OR REPLACE FUNCTION aq_sig_prop_get_timestamp(sig_data JSONB)
RETURNS BIGINT AS $$
BEGIN
    RETURN (sig_data->'metadata'->>'timestamp')::BIGINT;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract timestamp as PostgreSQL timestamp
CREATE OR REPLACE FUNCTION aq_sig_prop_get_timestamp_pg(sig_data JSONB)
RETURNS TIMESTAMP AS $$
BEGIN
    RETURN to_timestamp((sig_data->'metadata'->>'timestamp')::BIGINT / 1000.0);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract signature type
CREATE OR REPLACE FUNCTION aq_sig_prop_get_type(sig_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN sig_data->>'signature_type';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract specific signature property by name
CREATE OR REPLACE FUNCTION aq_sig_prop_get_property(sig_data JSONB, property_name TEXT)
RETURNS TEXT AS $$
BEGIN
    RETURN sig_data->'signature_properties'->>property_name;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if signature property exists
CREATE OR REPLACE FUNCTION aq_sig_prop_has_property(sig_data JSONB, property_name TEXT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN sig_data->'signature_properties' ? property_name;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get all signature property names as array
CREATE OR REPLACE FUNCTION aq_sig_prop_get_property_names(sig_data JSONB)
RETURNS TEXT[] AS $$
BEGIN
    RETURN ARRAY(SELECT jsonb_object_keys(sig_data->'signature_properties'));
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if signature is valid (has required fields)
CREATE OR REPLACE FUNCTION aq_sig_prop_is_valid_signature(sig_data JSONB)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN sig_data IS NOT NULL 
           AND sig_data ? 'signature_type'
           AND sig_data->>'signature_type' = 'AQ_SIG_PROP'
           AND sig_data ? 'signature_properties'
           AND sig_data->'signature_properties' ? 'algorithm';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if signature validation passed
CREATE OR REPLACE FUNCTION aq_sig_prop_is_signature_valid(sig_data JSONB)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN aq_sig_prop_get_validation_status(sig_data) = 'VALID';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if signature validation failed
CREATE OR REPLACE FUNCTION aq_sig_prop_is_signature_invalid(sig_data JSONB)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN aq_sig_prop_get_validation_status(sig_data) = 'INVALID';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if signature uses specific algorithm
CREATE OR REPLACE FUNCTION aq_sig_prop_uses_algorithm(sig_data JSONB, algorithm_name TEXT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN UPPER(aq_sig_prop_get_algorithm(sig_data)) = UPPER(algorithm_name);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract all metadata as JSONB
CREATE OR REPLACE FUNCTION aq_sig_prop_get_metadata(sig_data JSONB)
RETURNS JSONB AS $$
BEGIN
    RETURN sig_data->'metadata';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract all signature properties as JSONB
CREATE OR REPLACE FUNCTION aq_sig_prop_get_all_properties(sig_data JSONB)
RETURNS JSONB AS $$
BEGIN
    RETURN sig_data->'signature_properties';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Extract technical information
CREATE OR REPLACE FUNCTION aq_sig_prop_get_technical_info(sig_data JSONB)
RETURNS JSONB AS $$
BEGIN
    RETURN sig_data->'technical_info';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get conversion timestamp
CREATE OR REPLACE FUNCTION aq_sig_prop_get_conversion_timestamp(sig_data JSONB)
RETURNS TIMESTAMP AS $$
BEGIN
    RETURN (sig_data->'technical_info'->>'conversion_timestamp')::TIMESTAMP;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Get original Oracle type
CREATE OR REPLACE FUNCTION aq_sig_prop_get_original_type(sig_data JSONB)
RETURNS TEXT AS $$
BEGIN
    RETURN sig_data->'technical_info'->>'original_type';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if signature has digest
CREATE OR REPLACE FUNCTION aq_sig_prop_has_digest(sig_data JSONB)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN sig_data->'signature_properties'->>'digest' IS NOT NULL 
           AND length(sig_data->'signature_properties'->>'digest') > 0;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Check if signature has actual signature value
CREATE OR REPLACE FUNCTION aq_sig_prop_has_signature_value(sig_data JSONB)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN sig_data->'signature_properties'->>'signature' IS NOT NULL 
           AND length(sig_data->'signature_properties'->>'signature') > 0;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Example usage queries (commented out):
/*
-- Find all valid signatures
SELECT * FROM your_table 
WHERE aq_sig_prop_is_signature_valid(your_sig_column);

-- Find signatures using SHA256 algorithm
SELECT * FROM your_table 
WHERE aq_sig_prop_uses_algorithm(your_sig_column, 'SHA256');

-- Get signature details
SELECT 
    aq_sig_prop_get_algorithm(your_sig_column) as algorithm,
    aq_sig_prop_get_validation_status(your_sig_column) as status,
    aq_sig_prop_get_timestamp_pg(your_sig_column) as signature_time,
    aq_sig_prop_get_signer(your_sig_column) as signer
FROM your_table
WHERE aq_sig_prop_is_valid_signature(your_sig_column);

-- Find signatures by signer
SELECT * FROM your_table 
WHERE aq_sig_prop_get_signer(your_sig_column) LIKE '%CN=MyCA%';

-- Find signatures with specific properties
SELECT * FROM your_table 
WHERE aq_sig_prop_has_property(your_sig_column, 'custom_prop')
  AND aq_sig_prop_get_property(your_sig_column, 'custom_prop') = 'value';

-- Find invalid or failed signatures
SELECT * FROM your_table 
WHERE aq_sig_prop_is_signature_invalid(your_sig_column)
   OR aq_sig_prop_get_validation_status(your_sig_column) = 'ERROR';

-- Get signatures created in last 24 hours
SELECT * FROM your_table 
WHERE aq_sig_prop_get_timestamp_pg(your_sig_column) > NOW() - INTERVAL '24 hours';

-- Create indexes for better performance on signature queries
CREATE INDEX idx_aq_sig_algorithm 
ON your_table ((aq_sig_prop_get_algorithm(your_sig_column)));

CREATE INDEX idx_aq_sig_validation_status 
ON your_table ((aq_sig_prop_get_validation_status(your_sig_column)));

CREATE INDEX idx_aq_sig_timestamp 
ON your_table ((aq_sig_prop_get_timestamp_pg(your_sig_column)));

CREATE INDEX idx_aq_sig_signer 
ON your_table USING GIN ((aq_sig_prop_get_signer(your_sig_column)) gin_trgm_ops);

-- Create functional index for valid signatures
CREATE INDEX idx_aq_sig_valid 
ON your_table ((aq_sig_prop_is_signature_valid(your_sig_column))) 
WHERE aq_sig_prop_is_signature_valid(your_sig_column) = true;
*/