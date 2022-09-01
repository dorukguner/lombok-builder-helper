package com.dguner.lombokbuilderhelper;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import org.junit.Test;

public class LombokBuilderInspectionTest extends LightJavaCodeInsightFixtureTestCase {

  /**
   * Defines path to files used for running tests.
   *
   * @return The path from this module's root directory ($MODULE_WORKING_DIR$) to the
   * directory containing files for these tests.
   */
  @Override
  protected String getTestDataPath() {
    return "src/test/resources";
  }

  /**
   * Given the name of a test file, runs comparing references inspection quick fix and tests
   * the results against a reference outcome file. File name pattern 'foo.java' and 'foo.after.java'
   * are matching before and after files in the testData directory.
   *
   * @param testName The name of the test file before comparing references inspection.
   */
  protected void doTest(@NotNull String testName) {
    // Initialize the test based on the testData file
    myFixture.configureByFile(testName + ".java");
    // Initialize the inspection and get a list of highlighted
    myFixture.enableInspections(new LombokBuilderInspection());
    List<HighlightInfo> highlightInfos = myFixture.doHighlighting();
    assertFalse(highlightInfos.isEmpty());
    // Get the quick fix action for comparing references inspection and apply it to the file
    final IntentionAction action = myFixture.findSingleIntention(LombokBuilderInspection.QUICK_FIX_NAME);
    assertNotNull(action);
    myFixture.launchAction(action);
    // Verify the results
    myFixture.checkResultByFile(testName + ".after.java");
  }

  /**
   * Test the '==' case.
   */
  @Test
  public void testRelationalEq() {
    doTest("Eq");
  }

  /**
   * Test the '!=' case.
   */
  public void testRelationalNeq() {
    doTest("Neq");
  }

}
