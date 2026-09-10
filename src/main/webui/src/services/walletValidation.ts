import type { AddressCheck, Balance, BalancePoint, ElectrumServer, FeeRates, PriceRates, ReceiveAddress, Transaction, TransactionDetails, TransactionInput, TransactionOutput, Utxo, WalletEnvelope, WalletSnapshot, WalletStatus } from '../types/wallet.ts';

export type Validator<T> = (value: unknown) => value is T;

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function integer(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value);
}

function nonNegativeInteger(value: unknown): value is number {
  return integer(value) && value >= 0;
}

function finiteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function nonNegativeNumber(value: unknown): value is number {
  return finiteNumber(value) && value >= 0;
}

function text(value: unknown): value is string {
  return typeof value === 'string';
}

function nullable<T>(validate: Validator<T>): Validator<T | null> {
  return (value): value is T | null => value === null || validate(value);
}

export function arrayOf<T>(validate: Validator<T>): Validator<T[]> {
  return (value): value is T[] => Array.isArray(value) && value.every(validate);
}

export function validBalance(value: unknown): value is Balance {
  return record(value) && integer(value.confirmed) && integer(value.unconfirmed) && integer(value.total);
}

export function validReceiveAddress(value: unknown): value is ReceiveAddress {
  return record(value) && nonNegativeInteger(value.index) && text(value.address) && text(value.path);
}

export function validTransaction(value: unknown): value is Transaction {
  return record(value) && text(value.txid) && integer(value.amount)
    && nonNegativeInteger(value.received) && nonNegativeInteger(value.sent)
    && integer(value.height) && nonNegativeInteger(value.confirmations)
    && nullable(nonNegativeInteger)(value.timestamp)
    && (value.type === 'received' || value.type === 'sent' || value.type === 'self')
    && arrayOf(text)(value.addresses);
}

export function validUtxo(value: unknown): value is Utxo {
  return record(value) && text(value.txid) && text(value.address)
    && nonNegativeInteger(value.vout) && nonNegativeInteger(value.value)
    && integer(value.height) && nonNegativeInteger(value.confirmations);
}

export function validSnapshot(value: unknown): value is WalletSnapshot {
  if (!record(value)) return false;
  const discovery = value.discovery;
  if (discovery != null && (!record(discovery) || typeof discovery.complete !== 'boolean'
    || !integer(discovery.addressLimit) || discovery.addressLimit <= 0
    || !integer(discovery.gapLimit) || discovery.gapLimit <= 0
    || !nonNegativeInteger(discovery.receiveScanned) || discovery.receiveScanned > discovery.addressLimit
    || !nonNegativeInteger(discovery.changeScanned) || discovery.changeScanned > discovery.addressLimit)) return false;
  return validBalance(value.balance) && validReceiveAddress(value.receiveAddress)
    && arrayOf(validTransaction)(value.transactions) && arrayOf(validUtxo)(value.utxos);
}

function validStatus(value: unknown): value is WalletStatus {
  return value === 'loading' || value === 'syncing' || value === 'live' || value === 'offline' || value === 'error';
}

export function validEnvelope(value: unknown): value is WalletEnvelope {
  return record(value) && nonNegativeInteger(value.version) && validStatus(value.status)
    && (value.updatedAt == null || nonNegativeInteger(value.updatedAt))
    && nullable(text)(value.message) && nullable(validSnapshot)(value.snapshot);
}

export function validPrices(value: unknown): value is PriceRates {
  return record(value) && nonNegativeNumber(value.eur) && nonNegativeNumber(value.usd) && nonNegativeInteger(value.timestamp);
}

export function validFees(value: unknown): value is FeeRates {
  return record(value) && nonNegativeNumber(value.fastest) && nonNegativeNumber(value.halfHour)
    && nonNegativeNumber(value.hour) && nonNegativeNumber(value.economy)
    && nonNegativeNumber(value.minimum) && nonNegativeInteger(value.timestamp);
}

export function validBalancePoint(value: unknown): value is BalancePoint {
  return record(value) && nonNegativeInteger(value.time) && integer(value.balanceSats)
    && nullable(finiteNumber)(value.valueEur) && nullable(finiteNumber)(value.valueUsd)
    && nullable(nonNegativeInteger)(value.priceTime) && typeof value.priceStale === 'boolean';
}

export function validServer(value: unknown): value is ElectrumServer {
  return record(value) && text(value.host) && integer(value.port) && value.port > 0 && value.port <= 65_535
    && typeof value.tls === 'boolean' && typeof value.connected === 'boolean'
    && nullable(text)(value.serverVersion) && nullable(text)(value.protocolVersion);
}

export function validAddressCheck(value: unknown): value is AddressCheck {
  return record(value) && text(value.address) && typeof value.belongs === 'boolean'
    && (value.chain === null || value.chain === 0 || value.chain === 1)
    && nullable(nonNegativeInteger)(value.index) && nullable(text)(value.path) && nonNegativeInteger(value.checked);
}

function validInput(value: unknown): value is TransactionInput {
  return record(value) && nullable(text)(value.txid) && nonNegativeInteger(value.vout)
    && nullable(text)(value.address) && nullable(nonNegativeInteger)(value.value) && typeof value.coinbase === 'boolean';
}

function validOutput(value: unknown): value is TransactionOutput {
  return record(value) && nonNegativeInteger(value.index) && nullable(text)(value.address)
    && nonNegativeInteger(value.value) && text(value.scriptHex);
}

export function validTransactionDetails(value: unknown): value is TransactionDetails {
  return record(value) && text(value.txid) && integer(value.version)
    && nonNegativeInteger(value.lockTime) && nonNegativeInteger(value.size)
    && arrayOf(validInput)(value.inputs) && arrayOf(validOutput)(value.outputs)
    && nullable(nonNegativeInteger)(value.totalInput) && nonNegativeInteger(value.totalOutput)
    && nullable(nonNegativeInteger)(value.fee);
}