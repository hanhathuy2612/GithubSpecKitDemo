-- Demo accounts (data-model.md). Initial password for all: demo-password-1
-- Stored only as a BCrypt (strength 12) hash, never in plain text (Constitution IV).
INSERT INTO user_account (email, password_hash, status, created_at, updated_at) VALUES
    ('alice@example.com',    '$2a$12$fX3fkCn/gWvNY5KDlAakG.mgZuNNd7J7SNk1jZHI6sMVVi5No6YHK', 'ACTIVE',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('bob@example.com',      '$2a$12$fX3fkCn/gWvNY5KDlAakG.mgZuNNd7J7SNk1jZHI6sMVVi5No6YHK', 'ACTIVE',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('disabled@example.com', '$2a$12$fX3fkCn/gWvNY5KDlAakG.mgZuNNd7J7SNk1jZHI6sMVVi5No6YHK', 'DISABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
