package org.learning.mldsa.dtos;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Data
public class SlipRequest {
    // senderId was removed deliberately — the sender is now always the authenticated caller
    // (see SlipController), never a value the client gets to state. receiverId stays: who to
    // send *to* is a legitimate choice for the client to make, unlike who it's *from*.
    private Long receiverId;

    private String title;
    private String organizationName;
    private String organizationAddress;

    private LocalDate date;
    private String employeeName;
    private String payPeriod;
    private String designation;
    private Integer workedDays;
    private String department;

    private String payerAccount;
    private String payeeAccount;
    private String beneficiaryBank;

    private List<SlipLineItem> earnings;
    private List<SlipLineItem> deductions;

    // Deliberately a plain field, not computed — a correct amount-to-words converter is
    // its own small project; safer to have whoever composes the slip type it than to ship
    // an unverified conversion algorithm on a financial document.
    private String amountInWords;

    public BigDecimal totalEarnings() {
        return sum(earnings);
    }

    public BigDecimal totalDeductions() {
        return sum(deductions);
    }

    public BigDecimal netPay() {
        return totalEarnings().subtract(totalDeductions());
    }

    private BigDecimal sum(List<SlipLineItem> items) {
        if (items == null) {
            return BigDecimal.ZERO;
        }
        return items.stream()
                .map(SlipLineItem::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
