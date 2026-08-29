package com.cardvault.scan

/**
 * Hand-authored [OcrFrame]s standing in for real ML Kit output.
 *
 * Geometry is a 1000x630 frame — roughly a card's 1.586 aspect ratio — with the bounding boxes
 * placed where the text actually sits on the physical article: bank mark at the top, the number
 * across the middle in the largest glyphs, the expiry block under it, the holder's name low on
 * the face. The parser leans on those positions, so fixtures that ignored them would test
 * nothing worth testing.
 */
object ScanFixtures {

    private const val W = 1000
    private const val H = 630

    /** Frame-relative bands, so a fixture reads as a card layout rather than a pile of numbers. */
    private fun bankMark(text: String) = OcrLine(text, left = 60, top = 40, right = 420, bottom = 82)
    private fun cornerMark(text: String) = OcrLine(text, left = 760, top = 44, right = 950, bottom = 80)
    private fun pan(text: String) = OcrLine(text, left = 60, top = 270, right = 900, bottom = 336)
    private fun label(text: String) = OcrLine(text, left = 60, top = 400, right = 250, bottom = 428)
    private fun expiry(text: String) = OcrLine(text, left = 270, top = 398, right = 400, bottom = 430)
    private fun holder(text: String) = OcrLine(text, left = 60, top = 500, right = 560, bottom = 542)

    private fun frame(vararg lines: OcrLine) = OcrFrame(lines.toList(), W, H)

    // ---------------------------------------------------------------- happy paths

    const val VISA_PAN = "4111111111111111"

    /** Textbook front face: 4x4 groups, bank top-left, name below the number. */
    val visaFront: OcrFrame = frame(
        bankMark("HDFC BANK"),
        cornerMark("VISA"),
        pan("4111 1111 1111 1111"),
        label("VALID THRU"),
        expiry("09/29"),
        holder("ARJUN MEHTA"),
    )

    const val RUPAY_PAN = "6521123456789012"

    /**
     * Indian debit card. These print **both** dates, which is the trap: the first regex match on
     * the frame is `05/22`, a card that expired years ago.
     */
    val rupayBothDates: OcrFrame = frame(
        bankMark("State Bank of India"),
        cornerMark("RuPay"),
        pan("6521 1234 5678 9012"),
        OcrLine("VALID FROM 05/22", left = 60, top = 400, right = 300, bottom = 428),
        OcrLine("VALID THRU 05/27", left = 340, top = 400, right = 590, bottom = 428),
        holder("PRIYA SHARMA"),
    )

    const val AMEX_PAN = "378282246310005"

    /**
     * Amex prints a 4-digit CID on the **front**, above and right of the number. Not a
     * hypothetical CVV — a real one, in the frame, every time an Amex is scanned.
     */
    val amexWithFrontCid: OcrFrame = frame(
        bankMark("AMERICAN EXPRESS"),
        OcrLine("3782", left = 700, top = 190, right = 790, bottom = 226),
        OcrLine("3782 822463 10005", left = 60, top = 270, right = 880, bottom = 336),
        label("EXPIRES END"),
        expiry("11/28"),
        holder("R K IYER"),
    )

    /**
     * Reverse face, where the number now often lives — printed inches from the security code.
     * The parser must read one and never the other.
     */
    val backFaceWithCvv: OcrFrame = frame(
        OcrLine("AUTHORIZED SIGNATURE", left = 60, top = 150, right = 420, bottom = 186),
        OcrLine("4111 1111 1111 1111", left = 60, top = 260, right = 780, bottom = 322),
        OcrLine("09/29", left = 60, top = 350, right = 190, bottom = 382),
        OcrLine("123", left = 820, top = 348, right = 900, bottom = 384),
        OcrLine("CUSTOMER SERVICE 1800 266 4332", left = 60, top = 470, right = 620, bottom = 500),
    )

    const val GARBLED_PAN = "4510151015101518"

    /**
     * Embossed digits misread as letters. Raw digit extraction yields only 12 characters here,
     * so this frame is unreadable without [NumericNormalizer] and trivially readable with it.
     */
    val garbledDigits: OcrFrame = frame(
        bankMark("AXIS BANK"),
        cornerMark("VISA"),
        pan("45IO 1S10 151O 1518"),
        label("VALID THRU"),
        expiry("03/30"),
        holder("NEHA GUPTA"),
    )

    /** ML Kit occasionally returns the number as two side-by-side lines on the same row. */
    val splitPanAcrossLines: OcrFrame = frame(
        bankMark("ICICI Bank Ltd"),
        OcrLine("4111 1111", left = 60, top = 270, right = 470, bottom = 336),
        OcrLine("1111 1111", left = 500, top = 272, right = 900, bottom = 338),
        label("VALID THRU"),
        expiry("07/28"),
        holder("VIKRAM RAO"),
    )

    // ------------------------------------------------------------- bank resolution

    /** `HDFC` and `HSBC` are four characters and two edits apart. */
    val hdfcCard: OcrFrame = frame(
        bankMark("HDFC BANK"),
        pan("4111 1111 1111 1111"),
        holder("ARJUN MEHTA"),
    )

    val hsbcCard: OcrFrame = frame(
        bankMark("HSBC"),
        pan("4111 1111 1111 1111"),
        holder("ARJUN MEHTA"),
    )

    /** Bank mark printed in title case rather than caps. */
    val mixedCaseBank: OcrFrame = frame(
        bankMark("Kotak Mahindra Bank"),
        pan("4111 1111 1111 1111"),
        label("VALID THRU"),
        expiry("12/27"),
        holder("SUNIL NAIR"),
    )

    /** No bank mark at all — a plain co-branded card. */
    val noBankMark: OcrFrame = frame(
        cornerMark("VISA"),
        pan("4111 1111 1111 1111"),
        label("VALID THRU"),
        expiry("12/27"),
        holder("SUNIL NAIR"),
    )

    // ---------------------------------------------------------------- negative cases

    /** No card anywhere in shot. Two uppercase words are not a cardholder. */
    val notACard: OcrFrame = frame(
        OcrLine("GROCERY STORE", left = 60, top = 40, right = 420, bottom = 82),
        OcrLine("MILK 2L", left = 60, top = 150, right = 300, bottom = 182),
        OcrLine("TOTAL 45.90", left = 60, top = 200, right = 320, bottom = 232),
        OcrLine("THANK YOU", left = 60, top = 300, right = 300, bottom = 332),
    )

    /** A long digit run that is not a card number: no Luhn, no known prefix, wrong grouping. */
    val phoneNumberOnly: OcrFrame = frame(
        OcrLine("HELPLINE 1800 266 4332 999", left = 60, top = 200, right = 620, bottom = 236),
    )

    val emptyFrame: OcrFrame = OcrFrame(emptyList(), W, H)
}
