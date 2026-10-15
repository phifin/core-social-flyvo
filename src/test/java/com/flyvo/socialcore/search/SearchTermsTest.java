package com.flyvo.socialcore.search;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.search.domain.SearchTerms;
import org.junit.jupiter.api.Test;

class SearchTermsTest {
  @Test
  void boundsPlainLanguageAndEscapesNamePrefixesWithoutAccentFolding() {
    assertThat(SearchTerms.parse("  HÀ  NỘI ").terms()).isEqualTo("hà nội");
    assertThat(SearchTerms.parse("ha\u0300 nô\u0323i").normalized()).isEqualTo("hà nội");
    assertThat(SearchTerms.parse("ab_%").prefix()).isEqualTo("ab\\_\\%%");
    assertThat(SearchTerms.parse("x' OR 1=1 --").terms()).isEqualTo("x or 1 1");
    for (String q : new String[] {"a", "%_!", "x".repeat(201), "a b c d e f g h i"})
      assertThatThrownBy(() -> SearchTerms.parse(q)).isInstanceOf(IllegalArgumentException.class);
  }
}
