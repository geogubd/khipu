package khipu.validators

import java.math.BigInteger
import khipu.crypto.ECDSASignature
import khipu.domain.Account
import khipu.domain.BlockHeader
import khipu.domain.SignedTransaction
import khipu.domain.Transaction
import khipu.util.BlockchainConfig
import khipu.vm.{ EvmConfig, UInt256 }

sealed trait SignedTransactionError
object SignedTransactionError {
  case object TransactionSignatureError extends SignedTransactionError
  final case class TransactionSyntaxError(reason: String) extends SignedTransactionError
  final case class TransactionNonceError(txNonce: UInt256, senderNonce: UInt256) extends SignedTransactionError {
    override def toString: String =
      s"${getClass.getSimpleName}(Got tx nonce $txNonce but sender in mpt is: $senderNonce)"
  }
  final case class TransactionNotEnoughGasForIntrinsicError(txGasLimit: Long, txIntrinsicGas: Long) extends SignedTransactionError {
    override def toString: String =
      s"${getClass.getSimpleName}(Tx gas limit ($txGasLimit) < tx intrinsic gas ($txIntrinsicGas))"
  }
  final case class TransactionSenderCantPayUpfrontCostError(upfrontCost: UInt256, senderBalance: UInt256) extends SignedTransactionError {
    override def toString: String =
      s"${getClass.getSimpleName}(Upfrontcost ($upfrontCost) > sender balance ($senderBalance))"
  }
  final case class TransactionGasLimitTooBigError(txGasLimit: Long, accumGasUsed: Long, blockGasLimit: Long) extends SignedTransactionError {
    override def toString: String =
      s"${getClass.getSimpleName}(Tx gas limit ($txGasLimit) + gas accum ($accumGasUsed) > block gas limit ($blockGasLimit))"
  }
}

trait SignedTransactionValidator {
  val secp256k1n = new BigInteger("115792089237316195423570985008687907852837564279074904382605163141518161494337")
  val TWO = BigInteger.valueOf(2)

  def validate(stx: SignedTransaction, senderAccount: Account, blockHeader: BlockHeader, upfrontGasCost: UInt256, accumGasUsed: Long): Either[SignedTransactionError, Unit]
}

final class SignedTransactionValidatorImpl(blockchainConfig: BlockchainConfig) extends SignedTransactionValidator {
  import SignedTransactionError._

  /**
   * Initial tests of intrinsic validity stated in Section 6 of YP
   *
   * @param stx                        Transaction to validate
   * @param senderAccount              Account of the sender of the tx
   * @param blockHeader                Container block
   * @param upfrontGasCost    The upfront gas cost of the tx
   * @param accumGasUsed               Total amount of gas spent prior this transaction within the container block
   * @return Transaction if valid, error otherwise
   */
  def validate(stx: SignedTransaction, senderAccount: Account, blockHeader: BlockHeader, upfrontGasCost: UInt256, accumGasUsed: Long): Either[SignedTransactionError, Unit] = {
    for {
      _ <- checkSyntacticValidity(stx)
      _ <- validateSignature(stx, blockHeader.number)
      _ <- validateNonce(stx, senderAccount.nonce)
      _ <- validateGasLimitEnoughForIntrinsicGas(stx, blockHeader.number)
      _ <- validateAccountHasEnoughGasToPayUpfrontCost(senderAccount.balance, upfrontGasCost)
      _ <- validateBlockHasEnoughGasLimitForTx(stx, accumGasUsed, blockHeader.gasLimit)
    } yield ()
  }

  /**
   * Validates if the transaction is syntactically valid (lengths of the transaction fields are correct)
   *
   * @param stx Transaction to validate
   * @return Either the validated transaction or TransactionSyntaxError if an error was detected
   */
  private def checkSyntacticValidity(stx: SignedTransaction): Either[SignedTransactionError, Unit] = {
    import stx.tx._
    import stx._
    import Transaction._

    val maxNonceValue = TWO.pow(8 * NonceLength) subtract BigInteger.ONE
    val maxGasValue = TWO.pow(8 * GasLength) subtract BigInteger.ONE
    val maxValue = TWO.pow(8 * ValueLength) subtract BigInteger.ONE
    val maxR = TWO.pow(8 * ECDSASignature.RLength) subtract BigInteger.ONE
    val maxS = TWO.pow(8 * ECDSASignature.SLength) subtract BigInteger.ONE

    if (nonce.compareTo(maxNonceValue) > 0)
      Left(TransactionSyntaxError(s"Invalid nonce: $nonce > $maxNonceValue"))
    else if (BigInteger.valueOf(gasLimit).compareTo(maxGasValue) > 0)
      Left(TransactionSyntaxError(s"Invalid gasLimit: $gasLimit > $maxGasValue"))
    else if (gasPrice.compareTo(maxGasValue) > 0)
      Left(TransactionSyntaxError(s"Invalid gasPrice: $gasPrice > $maxGasValue"))
    else if (value.compareTo(maxValue) > 0)
      Left(TransactionSyntaxError(s"Invalid value: $value > $maxValue"))
    else if (signature.r.compareTo(maxR) > 0)
      Left(TransactionSyntaxError(s"Invalid signatureRandom: ${signature.r} > $maxR"))
    else if (signature.s.compareTo(maxS) > 0)
      Left(TransactionSyntaxError(s"Invalid signature: ${signature.s} > $maxS"))
    else
      Right(())
  }

  /**
   * Validates if the transaction signature is valid as stated in appendix F in YP
   *
   * @param stx                  Transaction to validate
   * @param blockNumber          Number of the block for this transaction
   * @return Either the validated transaction or TransactionSignatureError if an error was detected
   */
  private def validateSignature(stx: SignedTransaction, blockNumber: Long): Either[SignedTransactionError, Unit] = {
    val r = stx.signature.r
    val s = stx.signature.s

    val beforeHomestead = blockNumber < blockchainConfig.homesteadBlockNumber
    val beforeEIP155 = blockNumber < blockchainConfig.eip155BlockNumber

    val validR = r.compareTo(BigInteger.ZERO) > 0 && r.compareTo(secp256k1n) < 0
    val validS = s.compareTo(BigInteger.ZERO) > 0 && s.compareTo(if (beforeHomestead) secp256k1n else secp256k1n divide TWO) < 0
    val validSigningSchema = if (beforeEIP155) !stx.isChainSpecific else true

    if (validR && validS && validSigningSchema)
      Right(())
    else
      Left(TransactionSignatureError)
  }

  /**
   * Validates if the transaction nonce matches current sender account's nonce
   *
   * @param stx Transaction to validate
   * @param senderNonce Nonce of the sender of the transaction
   * @return Either the validated transaction or a TransactionNonceError
   */
  private def validateNonce(stx: SignedTransaction, senderNonce: UInt256): Either[SignedTransactionError, Unit] = {
    if (senderNonce == UInt256(stx.tx.nonce))
      Right(())
    else
      Left(TransactionNonceError(UInt256(stx.tx.nonce), senderNonce))
  }

  /**
   * Validates the gas limit is no smaller than the intrinsic gas used by the transaction.
   *
   * @param stx Transaction to validate
   * @param blockHeaderNumber Number of the block where the stx transaction was included
   * @return Either the validated transaction or a TransactionNotEnoughGasForIntrinsicError
   */
  private def validateGasLimitEnoughForIntrinsicGas(stx: SignedTransaction, blockHeaderNumber: Long): Either[SignedTransactionError, Unit] = {
    import stx.tx
    val config = EvmConfig.forBlock(blockHeaderNumber, blockchainConfig)
    val txIntrinsicGas = config.calcTransactionIntrinsicGas(tx.payload, tx.isContractCreation)
    if (stx.tx.gasLimit >= txIntrinsicGas)
      Right(())
    else
      Left(TransactionNotEnoughGasForIntrinsicError(stx.tx.gasLimit, txIntrinsicGas))
  }

  /**
   * Validates the sender account balance contains at least the cost required in up-front payment.
   *
   * @param senderBalance Balance of the sender of the tx
   * @param upfrontCost Upfront cost of the transaction tx
   * @return Either the validated transaction or a TransactionSenderCantPayUpfrontCostError
   */
  private def validateAccountHasEnoughGasToPayUpfrontCost(senderBalance: UInt256, upfrontCost: UInt256): Either[SignedTransactionError, Unit] = {
    if (senderBalance >= upfrontCost)
      Right(())
    else
      Left(TransactionSenderCantPayUpfrontCostError(upfrontCost, senderBalance))
  }

  /**
   * The sum of the transaction’s gas limit and the gas utilised in this block prior must be no greater than the
   * block’s gasLimit
   *
   * @param stx           Transaction to validate
   * @param accumGasUsed Gas spent within tx container block prior executing stx
   * @param blockGasLimit Block gas limit
   * @return Either the validated transaction or a TransactionGasLimitTooBigError
   */
  private def validateBlockHasEnoughGasLimitForTx(stx: SignedTransaction, accumGasUsed: Long, blockGasLimit: Long): Either[SignedTransactionError, Unit] = {
    if (stx.tx.gasLimit + accumGasUsed <= blockGasLimit)
      Right(())
    else
      Left(TransactionGasLimitTooBigError(stx.tx.gasLimit, accumGasUsed, blockGasLimit))
  }
}

