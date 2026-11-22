//! Every Redis key the server uses. Only the cache module can build a `RedisKey`, and each one
//! starts with a prefix from `NAMESPACES`, so text from a caller is only ever the part after it.

const NAMESPACES: [&str; 4] = [
    "k1:",
    "lnurl_invoice:",
    "email_verification:",
    "maintenance:",
];
const K1: &str = NAMESPACES[0];
const LNURL_INVOICE: &str = NAMESPACES[1];
const EMAIL_VERIFICATION: &str = NAMESPACES[2];
const MAINTENANCE: &str = NAMESPACES[3];

/// Prefixes an older server version may still write while it runs beside this one in a deploy.
#[cfg(test)]
const RETIRED: [(&str, &str); 1] = [(
    "invoice:",
    "a server from before the payee binding writes `invoice:<transaction_id>` with an ID the \
     submitter chooses",
)];

/// A Redis key. Code outside `cache` cannot build one from a string:
///
/// ```compile_fail,E0624
/// let _ = server::cache::keyspace::RedisKey::k1("invoice:victim");
/// ```
///
/// and the client takes nothing else as a key:
///
/// ```compile_fail,E0308
/// async fn login(redis: &server::cache::redis_client::RedisClient, k1: &str) {
///     let _: Option<i64> = redis.get_del(k1).await.unwrap();
/// }
/// ```
pub struct RedisKey(String);

impl RedisKey {
    pub(super) fn k1(k1: &str) -> Self {
        Self(format!("{K1}{k1}"))
    }

    /// Keyed by the payee as well, so only the account the request was pushed to can answer it.
    pub(super) fn lnurl_invoice(payee: &str, transaction_id: &str) -> Self {
        Self(format!("{LNURL_INVOICE}{payee}:{transaction_id}"))
    }

    pub(super) fn email_verification_code(pubkey: &str) -> Self {
        Self(format!("{EMAIL_VERIFICATION}{pubkey}:code"))
    }

    pub(super) fn email_verification_email(pubkey: &str) -> Self {
        Self(format!("{EMAIL_VERIFICATION}{pubkey}:email"))
    }

    pub(super) fn last_round_timestamp() -> Self {
        Self(format!("{MAINTENANCE}last_round_timestamp"))
    }

    pub(super) fn round_counter() -> Self {
        Self(format!("{MAINTENANCE}round_counter"))
    }

    pub(super) fn as_str(&self) -> &str {
        &self.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn namespaces_do_not_overlap() {
        for (i, a) in NAMESPACES.iter().enumerate() {
            for b in &NAMESPACES[i + 1..] {
                assert!(
                    !a.starts_with(b) && !b.starts_with(a),
                    "Redis namespaces {a:?} and {b:?} overlap, so one store can read or delete \
                     the other's keys. Give one of them a prefix the other does not start with."
                );
            }
            for (retired, reason) in RETIRED {
                assert!(
                    !a.starts_with(retired) && !retired.starts_with(a),
                    "Redis namespace {a:?} overlaps the retired prefix {retired:?}: {reason}. \
                     Pick a prefix that neither starts with nor is a start of {retired:?}."
                );
            }
        }
    }

    #[test]
    fn keys_render_as_the_previous_server_wrote_them() {
        let rendered = [
            RedisKey::k1("ab_1"),
            RedisKey::lnurl_invoice("02pk", "tx"),
            RedisKey::email_verification_code("02pk"),
            RedisKey::email_verification_email("02pk"),
            RedisKey::last_round_timestamp(),
            RedisKey::round_counter(),
        ]
        .map(|key| key.0);
        assert_eq!(
            rendered,
            [
                "k1:ab_1",
                "lnurl_invoice:02pk:tx",
                "email_verification:02pk:code",
                "email_verification:02pk:email",
                "maintenance:last_round_timestamp",
                "maintenance:round_counter",
            ]
        );
    }
}
