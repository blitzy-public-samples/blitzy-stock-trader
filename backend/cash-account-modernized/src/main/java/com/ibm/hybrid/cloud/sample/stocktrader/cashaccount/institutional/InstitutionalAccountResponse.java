package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.institutional;

import java.math.BigDecimal;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;

/**
 * The owner's cash position in one view: what is spendable now, what is held, and the two together.
 *
 * @param owner            the stored uppercase owner
 * @param currency         the account currency
 * @param availableBalance the spendable balance, which is what the retail surface reports as {@code balance}
 * @param reservedBalance  the balance held by reservations that have not yet settled or released
 * @param totalBalance     the sum of the two, derived rather than stored
 */
public record InstitutionalAccountResponse(
        String owner,

        String currency,

        // Split where the legacy carried one mutable BALANCE DECIMAL(9, 2) that credit and debit recomputed
        // outright [backend/cash-account-cobol/COBOL/DCLCASH.cpy:L10, COBOL/CASH00.cbl:L222, L225], so held
        // funds were indistinguishable from spendable ones; retail reports the available part alone, and this
        // view is the only place a caller sees both halves.
        BigDecimal availableBalance,

        BigDecimal reservedBalance,

        // Derived, never stored: taken from the entity's own totalBalance() rather than summed here, so the
        // wire value cannot drift from the domain's arithmetic, and a BigDecimal because two independently
        // bounded columns can legitimately sum past Money's ceiling and a read must not fail on that.
        BigDecimal totalBalance) {

    /**
     * Renders {@code account} as the institutional account view.
     *
     * @param account the account to project; its balances are read, never modified
     * @return the flat five-field view of that account's position
     * @throws IllegalArgumentException if {@code account} is null, which is a caller defect rather than a
     *         request condition and so carries no {@code CashAccountErrorCode}
     */
    public static InstitutionalAccountResponse from(CashAccount account) {
        if (account == null) {
            throw new IllegalArgumentException("account is required");
        }
        return new InstitutionalAccountResponse(
                account.owner(),
                account.currency(),
                account.availableBalance().amount(),
                account.reservedBalance().amount(),
                account.totalBalance());
    }
}
