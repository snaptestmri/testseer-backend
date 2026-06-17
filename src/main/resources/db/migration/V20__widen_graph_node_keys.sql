-- V20: Widen graph node keys — composite ids (serviceId::class::fqn) and gate/topic ids can exceed 255 chars
-- when method-call resolution captures long expressions or deep package names.

ALTER TABLE graph_nodes
    ALTER COLUMN id TYPE VARCHAR(1024);

ALTER TABLE graph_nodes
    ALTER COLUMN symbol_fqn TYPE VARCHAR(2048);

ALTER TABLE graph_edges
    ALTER COLUMN from_node TYPE VARCHAR(1024);

ALTER TABLE graph_edges
    ALTER COLUMN to_node TYPE VARCHAR(1024);
