package org.learning.mldsa.dtos;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Data
public class SlipRequest {
    // No senderId: the sender is whoever the request's token says it is, taken from the
    // authentication principal in SlipController. A slip that named its own sender would
    // let any caller compose one "from" another institution.
    private Long receiverId;

    private String title;
    private String organizationName;

    // Structured rather than a free-text line, because this becomes the debtor's PstlAdr in
    // the ISO 20022 payload and that requires discrete components. See PostalAddress.
    private PostalAddress organizationAddress;

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
