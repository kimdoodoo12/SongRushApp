package com.songrush.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

// Cycle 106 acceptance 1: CdnUrls swaps only the scheme and host of a presigned URL for the CDN host.
// The raw (still-encoded) path and query are kept byte for byte, so S3 still accepts the signature.
class CdnUrlsTest {

	private static final String CDN_HOST = "d1234abcd.cloudfront.net";

	private static final String S3_ORIGIN = "https://test-bucket.s3.ap-southeast-2.amazonaws.com";

	// A realistic presigned GetObject URL: a key with encoded characters, the credential scope with
	// %2F, a session token with %2B, %2F and %3D, a 60-second lifetime and a hex signature.
	private static final String PATH_AND_QUERY = "/a/Q7m%2BZx2Lp9%3DsVb4nWk1rTy0A"
			+ "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
			+ "&X-Amz-Date=20261110T120000Z"
			+ "&X-Amz-SignedHeaders=host"
			+ "&X-Amz-Credential=AKIDFAKETEST0000000%2F20261110%2Fap-southeast-2%2Fs3%2Faws4_request"
			+ "&X-Amz-Expires=60"
			+ "&X-Amz-Security-Token=IQoJb3JpZ2luX2VjE%2BH%2F%2Fwz%3D%3D"
			+ "&X-Amz-Signature=4f0e2c3b1a9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f";

	private static final String PRESIGNED = S3_ORIGIN + PATH_AND_QUERY;

	// Literal '+' and '=' (not percent-encoded) must survive as well: nothing is decoded or re-encoded.
	private static final String PATH_AND_QUERY_LITERAL = "/c/Ab+Cd=Ef"
			+ "?X-Amz-Credential=AKIDFAKETEST0000000%2F20261110%2Fap-southeast-2%2Fs3%2Faws4_request"
			+ "&X-Amz-Expires=60"
			+ "&X-Amz-Security-Token=ab+cd/ef=="
			+ "&X-Amz-Signature=00ff";

	@Test
	void hostIsReplacedAndSchemeIsHttps() {
		URI rewritten = URI.create(CdnUrls.rewrite(PRESIGNED, CDN_HOST));
		assertThat(rewritten.getScheme()).isEqualTo("https");
		assertThat(rewritten.getHost()).isEqualTo(CDN_HOST);
		assertThat(rewritten.getPort()).as("no port").isEqualTo(-1);
		assertThat(rewritten.getRawUserInfo()).isNull();
		assertThat(rewritten.getRawFragment()).isNull();
	}

	@Test
	void rawPathAndQueryAreUnchangedByteForByte() {
		String rewritten = CdnUrls.rewrite(PRESIGNED, CDN_HOST);
		assertThat(rewritten).isEqualTo("https://" + CDN_HOST + PATH_AND_QUERY);

		URI before = URI.create(PRESIGNED);
		URI after = URI.create(rewritten);
		assertThat(after.getRawPath()).isEqualTo(before.getRawPath());
		assertThat(after.getRawQuery()).isEqualTo(before.getRawQuery());
	}

	@Test
	void everyAmzParameterIsKeptInOrder() {
		String rawQuery = URI.create(CdnUrls.rewrite(PRESIGNED, CDN_HOST)).getRawQuery();
		assertThat(List.of(rawQuery.split("&"))).containsExactly(
				"X-Amz-Algorithm=AWS4-HMAC-SHA256",
				"X-Amz-Date=20261110T120000Z",
				"X-Amz-SignedHeaders=host",
				"X-Amz-Credential=AKIDFAKETEST0000000%2F20261110%2Fap-southeast-2%2Fs3%2Faws4_request",
				"X-Amz-Expires=60",
				"X-Amz-Security-Token=IQoJb3JpZ2luX2VjE%2BH%2F%2Fwz%3D%3D",
				"X-Amz-Signature=4f0e2c3b1a9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f");
	}

	@Test
	void literalPlusAndEqualsSignsAreNotReencoded() {
		assertThat(CdnUrls.rewrite(S3_ORIGIN + PATH_AND_QUERY_LITERAL, CDN_HOST))
				.isEqualTo("https://" + CDN_HOST + PATH_AND_QUERY_LITERAL);
	}

	@Test
	void anotherValidHostIsUsedAsGiven() {
		assertThat(CdnUrls.rewrite(PRESIGNED, "media.example.com"))
				.isEqualTo("https://media.example.com" + PATH_AND_QUERY);
	}

	@Test
	void nullHostLeavesTheUrlUnchanged() {
		assertThat(CdnUrls.rewrite(PRESIGNED, null)).isEqualTo(PRESIGNED);
	}

	@Test
	void blankHostLeavesTheUrlUnchanged() {
		assertThat(CdnUrls.rewrite(PRESIGNED, "")).isEqualTo(PRESIGNED);
		assertThat(CdnUrls.rewrite(PRESIGNED, "   ")).isEqualTo(PRESIGNED);
	}

	@Test
	void validHostAcceptsLowercaseDnsNames() {
		assertThat(CdnUrls.validHost("d1234abcd.cloudfront.net")).isTrue();
		assertThat(CdnUrls.validHost("media.example.com")).isTrue();
	}

	@Test
	void validHostRejectsSchemePathPortCaseSingleLabelWhitespaceAndEmpty() {
		for (String bad : List.of("https://x.cloudfront.net", "x.cloudfront.net/", "x.cloudfront.net:443",
				"UPPER.cloudfront.net", "localhost", "a b.com", "")) {
			assertThat(CdnUrls.validHost(bad)).as("validHost(\"%s\")", bad).isFalse();
		}
	}

}
