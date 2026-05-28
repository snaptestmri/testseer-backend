CREATE TABLE graph_nodes (
    id          VARCHAR(255) PRIMARY KEY,
    org_id      VARCHAR(100) NOT NULL,
    repo        VARCHAR(255) NOT NULL,
    service     VARCHAR(255) NOT NULL,
    module_type VARCHAR(50)  NOT NULL DEFAULT 'service',
    node_type   VARCHAR(50)  NOT NULL,
    symbol_fqn  VARCHAR(500)
);

CREATE TABLE graph_edges (
    id              BIGSERIAL    PRIMARY KEY,
    from_node       VARCHAR(255) NOT NULL REFERENCES graph_nodes(id),
    to_node         VARCHAR(255) NOT NULL REFERENCES graph_nodes(id),
    edge_type       VARCHAR(50)  NOT NULL,
    confidence      FLOAT        NOT NULL DEFAULT 1.0,
    evidence_source VARCHAR(50)
);

CREATE INDEX idx_edges_from    ON graph_edges(from_node, edge_type);
CREATE INDEX idx_edges_to      ON graph_edges(to_node,   edge_type);
CREATE INDEX idx_nodes_fqn     ON graph_nodes(symbol_fqn);
CREATE INDEX idx_nodes_service ON graph_nodes(org_id, service);
