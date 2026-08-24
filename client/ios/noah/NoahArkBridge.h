#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface NoahArkWalletConfiguration : NSObject

@property(nonatomic) BOOL regtest;
@property(nonatomic) BOOL signet;
@property(nonatomic) BOOL bitcoin;
@property(nonatomic, copy) NSString *arkURL;
@property(nonatomic, copy) NSString *userAgent;
@property(nonatomic, copy) NSString *esploraURL;
@property(nonatomic, copy) NSString *bitcoindURL;
@property(nonatomic, copy) NSString *bitcoindCookie;
@property(nonatomic, copy) NSString *bitcoindUser;
@property(nonatomic, copy) NSString *bitcoindPassword;
@property(nonatomic) uint32_t vtxoRefreshExpiryThreshold;
@property(nonatomic) uint64_t fallbackFeeRate;
@property(nonatomic) uint16_t htlcReceiveClaimDelta;
@property(nonatomic) uint16_t vtxoExitMargin;
@property(nonatomic) uint32_t roundTransactionRequiredConfirmations;

@end

@interface NoahArkOffchainBalance : NSObject

@property(nonatomic, readonly) uint64_t spendable;
@property(nonatomic, readonly) uint64_t pendingLightningSend;
@property(nonatomic, readonly) uint64_t claimableLightningReceive;
@property(nonatomic, readonly) uint64_t pendingInRound;
@property(nonatomic, readonly) uint64_t pendingExit;
@property(nonatomic, readonly) uint64_t pendingBoard;

- (instancetype)init NS_UNAVAILABLE;

@end

@interface NoahArkOnchainBalance : NSObject

@property(nonatomic, readonly) uint64_t immature;
@property(nonatomic, readonly) uint64_t trustedPending;
@property(nonatomic, readonly) uint64_t untrustedPending;
@property(nonatomic, readonly) uint64_t confirmed;

- (instancetype)init NS_UNAVAILABLE;

@end

@interface NoahArkBolt11Invoice : NSObject

@property(nonatomic, copy, readonly) NSString *paymentRequest;
@property(nonatomic, copy, readonly) NSString *paymentSecret;
@property(nonatomic, copy, readonly) NSString *paymentHash;

- (instancetype)init NS_UNAVAILABLE;

@end

@interface NoahArkBridge : NSObject

+ (BOOL)isWalletLoaded;

+ (BOOL)loadWalletAtPath:(NSString *)dataDirectory
                 mnemonic:(NSString *)mnemonic
             configuration:(NoahArkWalletConfiguration *)configuration
                     error:(NSError **)error;

+ (BOOL)syncWithError:(NSError **)error;
+ (BOOL)onchainSyncWithError:(NSError **)error;

+ (nullable NoahArkOffchainBalance *)offchainBalanceWithError:(NSError **)error;
+ (nullable NoahArkOnchainBalance *)onchainBalanceWithError:(NSError **)error;
+ (nullable NSString *)newAddressWithError:(NSError **)error;

+ (nullable NoahArkBolt11Invoice *)bolt11InvoiceForAmountSat:(uint64_t)amountSat
                                                  description:(nullable NSString *)description
                                                        token:(nullable NSString *)token
                                                        error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
