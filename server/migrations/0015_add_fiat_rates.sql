CREATE TABLE fiat_rates (
    currency TEXT NOT NULL,
    rate_date DATE NOT NULL,
    btc_price DOUBLE PRECISION NOT NULL CHECK (btc_price > 0),
    observed_at TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (currency, rate_date)
);

CREATE INDEX fiat_rates_observed_at_idx ON fiat_rates (observed_at DESC);
