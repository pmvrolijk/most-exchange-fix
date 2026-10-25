package nl.lamia.most.exchange.fixadapter.session

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Design.md §5.4: the acceptor refuses to start unless Artio's three validation switches are on. */
class CodecValidationTest {

    @Test
    fun `each of Artio's validation switches off is a refusal to start, naming it`() {
        val noValidation = assertFailsWith<IllegalStateException> { CodecValidation.require(validation = false, unknownEnumValues = true, unknownFields = true) }
        assertTrue("fix.codecs.no_validation" in noValidation.message!!)
        val noEnums = assertFailsWith<IllegalStateException> { CodecValidation.require(validation = true, unknownEnumValues = false, unknownFields = true) }
        assertTrue("fix.codecs.disable_reject_unknown_enum_value" in noEnums.message!!)
        val noFields = assertFailsWith<IllegalStateException> { CodecValidation.require(validation = true, unknownEnumValues = true, unknownFields = false) }
        assertTrue("fix.codecs.reject_unknown_field=true" in noFields.message!!)
    }

    @Test
    fun `this JVM, started as the adapter is, has all three on`() {
        CodecValidation.require()
    }
}
