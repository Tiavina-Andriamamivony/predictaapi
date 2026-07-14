-- Applications clientes : une clé API par app pour authentifier les appels.
-- Lookup par api_key au moment de l'auth -> UNIQUE (sert aussi d'index).
CREATE TABLE applications (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name    TEXT NOT NULL,
    api_key TEXT NOT NULL UNIQUE
);
