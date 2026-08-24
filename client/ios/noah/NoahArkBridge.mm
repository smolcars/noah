#import "NoahArkBridge.h"

#include <ark_cxx.h>
#include <exception>
#include <memory>
#include <string>

namespace {

NSString *const NoahArkBridgeErrorDomain = @"com.noahwallet.ark-bridge";

std::string ToStdString(NSString *value) {
  const char *utf8 = value.UTF8String;
  return utf8 == nullptr ? std::string() : std::string(utf8);
}

NSString *ToNSString(const rust::String &value) {
  return [[NSString alloc] initWithBytes:value.data()
                                  length:value.length()
                                encoding:NSUTF8StringEncoding];
}

void AssignError(NSError **error, NSString *operation, const char *message) {
  if (error == nullptr) {
    return;
  }

  NSString *reason = message == nullptr ? @"Unknown wallet error" : [NSString stringWithUTF8String:message];
  *error = [NSError errorWithDomain:NoahArkBridgeErrorDomain
                               code:1
                           userInfo:@{
                             NSLocalizedDescriptionKey: [NSString stringWithFormat:@"%@ failed: %@", operation, reason]
                           }];
}

template <typename Operation>
BOOL PerformVoidOperation(NSString *name, NSError **error, Operation operation) {
  try {
    operation();
    return YES;
  } catch (const std::exception &exception) {
    AssignError(error, name, exception.what());
    return NO;
  } catch (...) {
    AssignError(error, name, "Unknown wallet error");
    return NO;
  }
}

}  // namespace

@implementation NoahArkWalletConfiguration

- (instancetype)init {
  self = [super init];
  if (self) {
    _arkURL = @"";
    _userAgent = @"";
    _esploraURL = @"";
    _bitcoindURL = @"";
    _bitcoindCookie = @"";
    _bitcoindUser = @"";
    _bitcoindPassword = @"";
  }
  return self;
}

@end

@interface NoahArkOffchainBalance ()

- (instancetype)initWithSpendable:(uint64_t)spendable
              pendingLightningSend:(uint64_t)pendingLightningSend
         claimableLightningReceive:(uint64_t)claimableLightningReceive
                     pendingInRound:(uint64_t)pendingInRound
                        pendingExit:(uint64_t)pendingExit
                       pendingBoard:(uint64_t)pendingBoard;

@end

@implementation NoahArkOffchainBalance

- (instancetype)initWithSpendable:(uint64_t)spendable
              pendingLightningSend:(uint64_t)pendingLightningSend
         claimableLightningReceive:(uint64_t)claimableLightningReceive
                     pendingInRound:(uint64_t)pendingInRound
                        pendingExit:(uint64_t)pendingExit
                       pendingBoard:(uint64_t)pendingBoard {
  self = [super init];
  if (self) {
    _spendable = spendable;
    _pendingLightningSend = pendingLightningSend;
    _claimableLightningReceive = claimableLightningReceive;
    _pendingInRound = pendingInRound;
    _pendingExit = pendingExit;
    _pendingBoard = pendingBoard;
  }
  return self;
}

@end

@interface NoahArkOnchainBalance ()

- (instancetype)initWithImmature:(uint64_t)immature
                  trustedPending:(uint64_t)trustedPending
                untrustedPending:(uint64_t)untrustedPending
                       confirmed:(uint64_t)confirmed;

@end

@implementation NoahArkOnchainBalance

- (instancetype)initWithImmature:(uint64_t)immature
                  trustedPending:(uint64_t)trustedPending
                untrustedPending:(uint64_t)untrustedPending
                       confirmed:(uint64_t)confirmed {
  self = [super init];
  if (self) {
    _immature = immature;
    _trustedPending = trustedPending;
    _untrustedPending = untrustedPending;
    _confirmed = confirmed;
  }
  return self;
}

@end

@interface NoahArkBolt11Invoice ()

- (instancetype)initWithPaymentRequest:(NSString *)paymentRequest
                          paymentSecret:(NSString *)paymentSecret
                            paymentHash:(NSString *)paymentHash;

@end

@implementation NoahArkBolt11Invoice

- (instancetype)initWithPaymentRequest:(NSString *)paymentRequest
                          paymentSecret:(NSString *)paymentSecret
                            paymentHash:(NSString *)paymentHash {
  self = [super init];
  if (self) {
    _paymentRequest = [paymentRequest copy];
    _paymentSecret = [paymentSecret copy];
    _paymentHash = [paymentHash copy];
  }
  return self;
}

@end

@implementation NoahArkBridge

+ (BOOL)isWalletLoaded {
  return bark_cxx::is_wallet_loaded();
}

+ (BOOL)loadWalletAtPath:(NSString *)dataDirectory
                 mnemonic:(NSString *)mnemonic
             configuration:(NoahArkWalletConfiguration *)configuration
                     error:(NSError **)error {
  return PerformVoidOperation(@"Load wallet", error, [&]() {
    bark_cxx::CreateOpts options;
    options.regtest = configuration.regtest;
    options.signet = configuration.signet;
    options.bitcoin = configuration.bitcoin;
    options.mnemonic = ToStdString(mnemonic);
    options.birthday_height = nullptr;
    options.config.ark = ToStdString(configuration.arkURL);
    options.config.user_agent = ToStdString(configuration.userAgent);
    options.config.esplora = ToStdString(configuration.esploraURL);
    options.config.bitcoind = ToStdString(configuration.bitcoindURL);
    options.config.bitcoind_cookie = ToStdString(configuration.bitcoindCookie);
    options.config.bitcoind_user = ToStdString(configuration.bitcoindUser);
    options.config.bitcoind_pass = ToStdString(configuration.bitcoindPassword);
    options.config.vtxo_refresh_expiry_threshold = configuration.vtxoRefreshExpiryThreshold;
    options.config.fallback_fee_rate = configuration.fallbackFeeRate;
    options.config.htlc_recv_claim_delta = configuration.htlcReceiveClaimDelta;
    options.config.vtxo_exit_margin = configuration.vtxoExitMargin;
    options.config.round_tx_required_confirmations = configuration.roundTransactionRequiredConfirmations;

    bark_cxx::load_wallet(ToStdString(dataDirectory), options);
  });
}

+ (BOOL)syncWithError:(NSError **)error {
  return PerformVoidOperation(@"Ark sync", error, []() { bark_cxx::sync(); });
}

+ (BOOL)onchainSyncWithError:(NSError **)error {
  return PerformVoidOperation(@"On-chain sync", error, []() { bark_cxx::onchain_sync(); });
}

+ (NoahArkOffchainBalance *)offchainBalanceWithError:(NSError **)error {
  try {
    const auto balance = bark_cxx::offchain_balance();
    return [[NoahArkOffchainBalance alloc]
        initWithSpendable:balance.spendable
        pendingLightningSend:balance.pending_lightning_send
        claimableLightningReceive:balance.claimable_lightning_receive
        pendingInRound:balance.pending_in_round
        pendingExit:balance.pending_exit
        pendingBoard:balance.pending_board];
  } catch (const std::exception &exception) {
    AssignError(error, @"Read off-chain balance", exception.what());
    return nil;
  } catch (...) {
    AssignError(error, @"Read off-chain balance", "Unknown wallet error");
    return nil;
  }
}

+ (NoahArkOnchainBalance *)onchainBalanceWithError:(NSError **)error {
  try {
    const auto balance = bark_cxx::onchain_balance();
    return [[NoahArkOnchainBalance alloc]
        initWithImmature:balance.immature
        trustedPending:balance.trusted_pending
        untrustedPending:balance.untrusted_pending
        confirmed:balance.confirmed];
  } catch (const std::exception &exception) {
    AssignError(error, @"Read on-chain balance", exception.what());
    return nil;
  } catch (...) {
    AssignError(error, @"Read on-chain balance", "Unknown wallet error");
    return nil;
  }
}

+ (NSString *)newAddressWithError:(NSError **)error {
  try {
    const auto result = bark_cxx::new_address();
    return ToNSString(result.address);
  } catch (const std::exception &exception) {
    AssignError(error, @"Create Ark address", exception.what());
    return nil;
  } catch (...) {
    AssignError(error, @"Create Ark address", "Unknown wallet error");
    return nil;
  }
}

+ (NoahArkBolt11Invoice *)bolt11InvoiceForAmountSat:(uint64_t)amountSat
                                          description:(NSString *)description
                                                token:(NSString *)token
                                                error:(NSError **)error {
  try {
    std::unique_ptr<rust::String> nativeDescription;
    std::unique_ptr<rust::String> nativeToken;
    if (description != nil) {
      nativeDescription = std::make_unique<rust::String>(ToStdString(description));
    }
    if (token != nil) {
      nativeToken = std::make_unique<rust::String>(ToStdString(token));
    }

    // The generated CXX argument is named amount_msat, but the current Bark API
    // and Noah's existing React Native integration both interpret it as sats.
    const auto invoice = bark_cxx::bolt11_invoice(
        amountSat, nativeDescription.get(), nativeToken.get());
    return [[NoahArkBolt11Invoice alloc]
        initWithPaymentRequest:ToNSString(invoice.bolt11_invoice)
        paymentSecret:ToNSString(invoice.payment_secret)
        paymentHash:ToNSString(invoice.payment_hash)];
  } catch (const std::exception &exception) {
    AssignError(error, @"Create BOLT11 invoice", exception.what());
    return nil;
  } catch (...) {
    AssignError(error, @"Create BOLT11 invoice", "Unknown wallet error");
    return nil;
  }
}

@end
