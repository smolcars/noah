//! Runs mailbox sessions through the real `Beta8MailboxTransport` against a
//! local gRPC server standing in for captaind, to check how many connections
//! the sessions open.

use std::{
    convert::Infallible,
    net::{Shutdown, SocketAddr},
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    time::Duration,
};

use chrono::Utc;
use futures_util::{StreamExt, stream};
use server_rpc::{
    MAX_PROTOCOL_VERSION, MIN_PROTOCOL_VERSION, protos,
    protos::mailbox_server::{MailboxMessage, MailboxMessages, MailboxRequest},
    tonic::{
        Request, Response, Status,
        body::Body,
        codegen::{BoxFuture, BoxStream, Context, Poll, Service, http},
        server::{Grpc, NamedService},
        transport::Server,
    },
};
use tokio::{
    net::{TcpListener, TcpStream},
    task::JoinSet,
};
use tokio_util::sync::CancellationToken;
use tonic_prost::ProstCodec;
use tower::service_fn;

use crate::{
    AppState, AppStruct,
    config::Config,
    db::mailbox_authorization_repo::ActiveMailboxAuthorization,
    mailbox_worker::{
        Beta8MailboxTransport, MailboxSessionContext, MailboxSessionOutcome, MailboxTransport,
        MailboxWorkerConfig,
    },
    tests::common::{TestUser, setup_test_app},
};

/// Subscriptions to this mailbox end straight away, like a dropped stream.
const ENDING_MAILBOX_ID: &str = "dead";
const MAILBOX_ID: &str = "deadbeef";

/// Answers the handshake and ArkInfo, returns empty mailboxes, and keeps
/// every other subscription open. Counts accepted TCP connections and can
/// close them.
#[derive(Clone, Default)]
struct MockArkServer {
    connections: Arc<AtomicUsize>,
    subscriptions: Arc<AtomicUsize>,
    sockets: Arc<Mutex<Vec<std::net::TcpStream>>>,
}

impl MockArkServer {
    async fn start() -> (Self, SocketAddr) {
        let server = Self::default();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let connections = server.connections.clone();
        let sockets = server.sockets.clone();
        let incoming = stream::unfold(listener, move |listener| {
            let connections = connections.clone();
            let sockets = sockets.clone();
            async move {
                // Keeps a second handle on each socket so the test can close it.
                let accepted = listener.accept().await.and_then(|(socket, _)| {
                    let socket = socket.into_std()?;
                    sockets.lock().unwrap().push(socket.try_clone()?);
                    TcpStream::from_std(socket)
                });
                connections.fetch_add(1, Ordering::SeqCst);
                Some((accepted, listener))
            }
        });
        let router = Server::builder()
            .add_service(ArkStub)
            .add_service(MailboxStub(server.clone()));
        tokio::spawn(router.serve_with_incoming(incoming));
        (server, addr)
    }

    fn connections(&self) -> usize {
        self.connections.load(Ordering::SeqCst)
    }

    fn subscriptions(&self) -> usize {
        self.subscriptions.load(Ordering::SeqCst)
    }

    /// Drops every open connection, like the Ark server restarting.
    fn close_connections(&self) {
        for socket in self.sockets.lock().unwrap().drain(..) {
            socket.shutdown(Shutdown::Both).unwrap();
        }
    }
}

fn regtest_ark_info() -> protos::ArkInfo {
    let pubkey = TestUser::new().pubkey().to_bytes();
    protos::ArkInfo {
        network: "regtest".to_string(),
        server_pubkey: pubkey.clone(),
        mailbox_pubkey: pubkey,
        round_interval_secs: 60,
        nb_round_nonces: 1,
        vtxo_exit_delta: 12,
        vtxo_lifetime: 100,
        htlc_send_expiry_delta: 100,
        htlc_expiry_delta: 6,
        required_board_confirmations: 1,
        max_user_invoice_cltv_delta: 50,
        max_vtxo_exit_depth: 5,
        max_offboard_inputs: 10,
        fees: Some(protos::FeeSchedule {
            board: Some(Default::default()),
            offboard: Some(Default::default()),
            refresh: Some(Default::default()),
            lightning_receive: Some(Default::default()),
            lightning_send: Some(Default::default()),
        }),
        ..Default::default()
    }
}

#[derive(Clone)]
struct ArkStub;

impl NamedService for ArkStub {
    const NAME: &'static str = "bark_server.ArkService";
}

impl Service<http::Request<Body>> for ArkStub {
    type Response = http::Response<Body>;
    type Error = Infallible;
    type Future = BoxFuture<Self::Response, Infallible>;

    fn poll_ready(&mut self, _cx: &mut Context<'_>) -> Poll<Result<(), Infallible>> {
        Poll::Ready(Ok(()))
    }

    fn call(&mut self, request: http::Request<Body>) -> Self::Future {
        Box::pin(async move {
            Ok(match request.uri().path() {
                "/bark_server.ArkService/Handshake" => {
                    let handshake = |_: Request<protos::HandshakeRequest>| async {
                        Ok::<_, Status>(Response::new(protos::HandshakeResponse {
                            min_protocol_version: MIN_PROTOCOL_VERSION,
                            max_protocol_version: MAX_PROTOCOL_VERSION,
                            psa: None,
                        }))
                    };
                    Grpc::new(ProstCodec::default())
                        .unary(service_fn(handshake), request)
                        .await
                }
                "/bark_server.ArkService/GetArkInfo" => {
                    let ark_info = |_: Request<protos::Empty>| async {
                        Ok::<_, Status>(Response::new(regtest_ark_info()))
                    };
                    Grpc::new(ProstCodec::default())
                        .unary(service_fn(ark_info), request)
                        .await
                }
                _ => Status::unimplemented("not mocked").into_http(),
            })
        })
    }
}

#[derive(Clone)]
struct MailboxStub(MockArkServer);

impl NamedService for MailboxStub {
    const NAME: &'static str = "mailbox_server.MailboxService";
}

impl Service<http::Request<Body>> for MailboxStub {
    type Response = http::Response<Body>;
    type Error = Infallible;
    type Future = BoxFuture<Self::Response, Infallible>;

    fn poll_ready(&mut self, _cx: &mut Context<'_>) -> Poll<Result<(), Infallible>> {
        Poll::Ready(Ok(()))
    }

    fn call(&mut self, request: http::Request<Body>) -> Self::Future {
        let subscriptions = self.0.subscriptions.clone();
        Box::pin(async move {
            Ok(match request.uri().path() {
                "/mailbox_server.MailboxService/ReadMailbox" => {
                    let read = |_: Request<MailboxRequest>| async {
                        Ok::<_, Status>(Response::new(MailboxMessages {
                            messages: vec![],
                            have_more: false,
                        }))
                    };
                    Grpc::new(ProstCodec::default())
                        .unary(service_fn(read), request)
                        .await
                }
                "/mailbox_server.MailboxService/SubscribeMailbox" => {
                    let subscribe = move |request: Request<MailboxRequest>| {
                        subscriptions.fetch_add(1, Ordering::SeqCst);
                        let ends =
                            request.get_ref().mailbox_id == hex::decode(ENDING_MAILBOX_ID).unwrap();
                        async move {
                            let messages: BoxStream<MailboxMessage> = if ends {
                                stream::empty().boxed()
                            } else {
                                stream::pending().boxed()
                            };
                            Ok::<_, Status>(Response::new(messages))
                        }
                    };
                    Grpc::new(ProstCodec::default())
                        .server_streaming(service_fn(subscribe), request)
                        .await
                }
                _ => Status::unimplemented("not mocked").into_http(),
            })
        })
    }
}

fn mock_ark_state(app_state: &AppState, addr: SocketAddr) -> AppState {
    Arc::new(AppStruct {
        config: Arc::new(Config {
            ark_server_url: format!("http://{addr}"),
            server_network: "regtest".to_string(),
            ..TestUser::get_config()
        }),
        ..(**app_state).clone()
    })
}

fn spawn_session(
    sessions: &mut JoinSet<anyhow::Result<MailboxSessionOutcome>>,
    transport: &Arc<Beta8MailboxTransport>,
    app_state: &AppState,
    mailbox_id: &str,
    stream_idle_reconnect: Duration,
    cancellation_token: &CancellationToken,
) {
    let transport = transport.clone();
    let app_state = app_state.clone();
    let mailbox = test_mailbox(mailbox_id);
    let session = MailboxSessionContext {
        worker_id: "transport-test".to_string(),
        stream_idle_reconnect,
        cancellation_token: cancellation_token.clone(),
    };
    sessions.spawn(async move { transport.run_session(app_state, mailbox, session).await });
}

fn test_mailbox(mailbox_id: &str) -> ActiveMailboxAuthorization {
    ActiveMailboxAuthorization {
        pubkey: "transport-test-pubkey".to_string(),
        mailbox_id: mailbox_id.to_string(),
        authorization_hex: "cafebabe".to_string(),
        authorization_expires_at: Utc::now().timestamp() + 3600,
        auth_version: 1,
        last_checkpoint: 1,
    }
}

async fn wait_until(what: &str, condition: impl Fn() -> bool) {
    tokio::time::timeout(Duration::from_secs(10), async {
        while !condition() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap_or_else(|_| panic!("timed out waiting for {what}"));
}

async fn assert_all_completed(sessions: &mut JoinSet<anyhow::Result<MailboxSessionOutcome>>) {
    while let Some(result) = sessions.join_next().await {
        assert_eq!(result.unwrap().unwrap(), MailboxSessionOutcome::Completed);
    }
}

#[tokio::test]
async fn mailbox_sessions_share_ark_server_connections() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let (server, addr) = MockArkServer::start().await;
    let app_state = mock_ark_state(&app_state, addr);
    let transport = Arc::new(Beta8MailboxTransport::new(&MailboxWorkerConfig {
        max_streams_per_channel: 3,
        max_channels: 4,
        ..Default::default()
    }));
    let cancel = CancellationToken::new();
    let idle_reconnect = Duration::from_secs(600);
    let mut sessions = JoinSet::new();

    // Sessions that start together share one dial and one connection.
    for _ in 0..3 {
        spawn_session(
            &mut sessions,
            &transport,
            &app_state,
            MAILBOX_ID,
            idle_reconnect,
            &cancel,
        );
    }
    wait_until("three subscriptions", || server.subscriptions() == 3).await;
    assert_eq!(server.connections(), 1);
    assert_eq!(transport.open_channels(), 1);

    // A fourth goes over the per-channel limit and gets a second connection.
    spawn_session(
        &mut sessions,
        &transport,
        &app_state,
        MAILBOX_ID,
        idle_reconnect,
        &cancel,
    );
    wait_until("four subscriptions", || server.subscriptions() == 4).await;
    assert_eq!(server.connections(), 2);
    assert_eq!(transport.open_channels(), 2);

    // A stream that ends is retryable. It ran on the second channel, the only
    // one with room, and that channel stays in the pool: the stream ending
    // says nothing about the connection under it.
    let outcome = transport
        .run_session(
            app_state.clone(),
            test_mailbox(ENDING_MAILBOX_ID),
            MailboxSessionContext {
                worker_id: "transport-test".to_string(),
                stream_idle_reconnect: idle_reconnect,
                cancellation_token: cancel.clone(),
            },
        )
        .await
        .unwrap();
    assert!(matches!(outcome, MailboxSessionOutcome::Retryable { .. }));
    assert_eq!(transport.open_channels(), 2);

    // The next session joins that channel again, on the same connection.
    spawn_session(
        &mut sessions,
        &transport,
        &app_state,
        MAILBOX_ID,
        idle_reconnect,
        &cancel,
    );
    wait_until("six subscriptions", || server.subscriptions() == 6).await;
    assert_eq!(server.connections(), 2);
    assert_eq!(transport.open_channels(), 2);

    cancel.cancel();
    assert_all_completed(&mut sessions).await;
}

#[tokio::test]
async fn mailbox_sessions_keep_the_pooled_channel_when_its_connection_closes() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let (server, addr) = MockArkServer::start().await;
    let app_state = mock_ark_state(&app_state, addr);
    let transport = Arc::new(Beta8MailboxTransport::new(&MailboxWorkerConfig::default()));
    let cancel = CancellationToken::new();
    let idle_reconnect = Duration::from_secs(600);
    let mut sessions = JoinSet::new();

    for _ in 0..2 {
        spawn_session(
            &mut sessions,
            &transport,
            &app_state,
            MAILBOX_ID,
            idle_reconnect,
            &cancel,
        );
    }
    wait_until("two subscriptions", || server.subscriptions() == 2).await;
    assert_eq!(server.connections(), 1);

    // Every stream on the connection fails with it, and each session ends
    // retryable so the scheduler backs off before running it again.
    server.close_connections();
    while let Some(result) = sessions.join_next().await {
        let outcome = result.unwrap().unwrap();
        assert!(matches!(outcome, MailboxSessionOutcome::Retryable { .. }));
    }
    assert_eq!(transport.open_channels(), 1);

    // The next session borrows the same pooled channel. tonic notices its
    // connection is gone and dials a new one under it on the first request,
    // so the server sees one more connection and the pool still holds one
    // channel.
    spawn_session(
        &mut sessions,
        &transport,
        &app_state,
        MAILBOX_ID,
        idle_reconnect,
        &cancel,
    );
    wait_until("a third subscription", || server.subscriptions() == 3).await;
    assert_eq!(server.connections(), 2);
    assert_eq!(transport.open_channels(), 1);

    cancel.cancel();
    assert_all_completed(&mut sessions).await;
}

#[tokio::test]
async fn mailbox_idle_reconnect_reuses_the_pooled_connection() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let (server, addr) = MockArkServer::start().await;
    let app_state = mock_ark_state(&app_state, addr);
    let transport = Arc::new(Beta8MailboxTransport::new(&MailboxWorkerConfig::default()));
    let cancel = CancellationToken::new();
    let mut sessions = JoinSet::new();

    spawn_session(
        &mut sessions,
        &transport,
        &app_state,
        MAILBOX_ID,
        Duration::from_millis(50),
        &cancel,
    );
    wait_until("three resubscriptions", || server.subscriptions() >= 3).await;
    assert_eq!(server.connections(), 1);
    assert_eq!(transport.open_channels(), 1);

    cancel.cancel();
    assert_all_completed(&mut sessions).await;
}
