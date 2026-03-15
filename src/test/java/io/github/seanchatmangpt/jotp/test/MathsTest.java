package io.github.seanchatmangpt.jotp.test;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Maths;
import io.github.seanchatmangpt.jotp.Maths.Input;
import io.github.seanchatmangpt.jotp.Maths.Output;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.BeforeEach;
class MathsTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    void test() {
        assertThat(new Maths().sum(new Input(1, 2))).isEqualTo(new Output(3));
}
