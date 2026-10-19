-- Connector 3.1.2 DBStore schema v1; inert RN-shaped test registrations.
CREATE TABLE distributors (distributor TEXT PRIMARY KEY, fallback_from TEXT, ack INTEGER, date_insertion INTEGER, FOREIGN KEY (fallback_from) REFERENCES distributors(distributor) ON DELETE CASCADE);
CREATE TABLE registrations (instance TEXT PRIMARY KEY, message TEXT, vapid TEXT);
CREATE TABLE tokens (connectorToken TEXT PRIMARY KEY, instance TEXT, distributor TEXT, FOREIGN KEY (instance) REFERENCES registrations(instance) ON DELETE CASCADE, FOREIGN KEY (distributor) REFERENCES distributors(distributor) ON DELETE CASCADE);
CREATE TABLE keys (instance TEXT PRIMARY KEY, auth TEXT, pubkey TEXT, privkey TEXT, iv TEXT, FOREIGN KEY (instance) REFERENCES registrations(instance) ON DELETE CASCADE);
INSERT INTO distributors VALUES ('__DISTRIBUTOR__', NULL, 1, 1700000000);
INSERT INTO registrations VALUES ('noah-mainnet', 'RN mainnet registration', NULL);
INSERT INTO registrations VALUES ('noah-signet', 'RN signet registration', NULL);
INSERT INTO tokens VALUES ('fixture-mainnet-token', 'noah-mainnet', '__DISTRIBUTOR__');
INSERT INTO tokens VALUES ('fixture-signet-token', 'noah-signet', '__DISTRIBUTOR__');
PRAGMA user_version = 1;
