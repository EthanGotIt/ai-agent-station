package cn.ethan.ai.test.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that loads {@code .env} from the module root before all
 * tests in a class are executed.
 */
public class DotenvExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        DotenvLoader.load();
    }
}
