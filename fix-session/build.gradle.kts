// The FIX side, shared by both backends: the Artio engine and library, the acceptor, logon and
// authentication, session persistence, and the FIX dictionary's generated codecs (docs/Design.md §2).

dependencies {
    api(libs.artio.core)
    api(libs.artio.codecs)
}
