CREATE TABLE APP_VERSION
(
    VERSION_VALUE NUMBER NOT NULL,
    CONSTRAINT APP_VERSION_PK PRIMARY KEY(VERSION_VALUE)
)
#GO
INSERT INTO APP_VERSION (VERSION_VALUE) VALUES (0)
#GO