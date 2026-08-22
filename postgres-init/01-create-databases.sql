-- Single Postgres instance, two logically separate databases (not just
-- schemas) — see README "Datastore layout" for the tradeoff vs. two
-- separate Postgres containers.
CREATE USER secureledger WITH PASSWORD 'secureledger';
CREATE DATABASE secureledger OWNER secureledger;

CREATE USER transactionwatch WITH PASSWORD 'transactionwatch';
CREATE DATABASE transactionwatch OWNER transactionwatch;
