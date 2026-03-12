package io.github.seanchatmangpt.jotp.test;

import io.github.seanchatmangpt.jotp.Maths;
import io.github.seanchatmangpt.jotp.Maths.Input;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.UseType;
import org.assertj.core.api.WithAssertions;
import org.assertj.core.api.WithAssumptions;

class MathsIT implements WithAssertions, WithAssumptions {

    @Property
    void sumTwoValuesIsValid(@ForAll @UseType Input input) {
        assertThat(new Maths().sum(input).result()).isBetween(Long.MIN_VALUE, Long.MAX_VALUE);
    }
}
