package nl.lamia.most.exchange.fixadapter.session

import uk.co.real_logic.artio.builder.RejectUnknownEnumValue
import uk.co.real_logic.artio.builder.RejectUnknownField
import uk.co.real_logic.artio.builder.Validation

/**
 * Artio's codec validation switches, which the adapter's refusals rest on (Design.md §5.4). Each is a
 * JVM-wide system property Artio reads once, so it cannot be set from the configuration: the
 * acceptor refuses to start without them.
 */
object CodecValidation {
    const val REJECT_UNKNOWN_FIELD = "fix.codecs.reject_unknown_field"

    fun require(
        validation: Boolean = Validation.CODEC_VALIDATION_ENABLED,
        unknownEnumValues: Boolean = RejectUnknownEnumValue.CODEC_REJECT_UNKNOWN_ENUM_VALUE_ENABLED,
        unknownFields: Boolean = RejectUnknownField.CODEC_REJECT_UNKNOWN_FIELD_ENABLED,
    ) {
        check(validation) { "Artio's codec validation is off: remove -Dfix.codecs.no_validation" }
        check(unknownEnumValues) { "Artio accepts unknown enum values: remove -Dfix.codecs.disable_reject_unknown_enum_value" }
        check(unknownFields) { "Artio accepts unknown tags: start the JVM with -D$REJECT_UNKNOWN_FIELD=true" }
    }
}
