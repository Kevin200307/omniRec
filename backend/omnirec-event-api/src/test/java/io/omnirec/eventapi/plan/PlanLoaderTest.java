// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.plan;

import io.omnirec.commerce.catalog.EventDefinition;
import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.catalog.FieldDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanLoaderTest {

    private final PlanLoader loader = new PlanLoader();
    private final EventRegistry standard = EventRegistry.standard();

    @TempDir
    Path dir;

    private String plan(String name, String yaml) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, yaml);
        return file.toUri().toString();
    }

    private List<String> problems(String... locations) {
        PlanException error = assertThrows(PlanException.class, () -> loader.load(standard, List.of(locations)));
        return error.problems();
    }

    @Test
    void loadsCustomEventsWithInlineFieldsVocabulariesAndBlockRefinements() throws IOException {
        String location = plan("ok.yaml", """
                vocabularies:
                  variant: [a, b]
                events:
                  action_x_clicked:
                    description: "Shopper clicked X."
                    domain: engagement
                    sources: [browser]
                    blocks: [product]
                    properties:
                      variant: { type: enum, vocabulary: variant, required: true }
                      secondsOpen: { type: integer, minimum: 0 }
                      product.id: { required: true }
                      product.price: { minimum: 1 }
                    example:
                      variant: a
                  newsletter_popup_closed:
                    properties:
                      subscribed: { type: boolean, required: true }
                """);

        PlanLoader.Plan plan = loader.load(standard, List.of(location));

        assertEquals(List.of("a", "b"), plan.vocabularies().get("variant"));
        EventDefinition clicked = plan.events().stream().filter(e -> e.name().equals("action_x_clicked")).findFirst().orElseThrow();
        assertEquals(EventDefinition.KIND_CUSTOM, clicked.kind());
        assertEquals("engagement", clicked.domain());
        assertEquals(List.of("browser"), clicked.sources());
        assertEquals(List.of("variant", "product.id"), clicked.required());
        FieldDefinition price = clicked.fields().get("product.price");
        assertEquals("money", price.type(), "refinements keep the block's type");
        assertEquals(0, price.minimum().compareTo(BigDecimal.ONE));
        assertEquals("a", clicked.example().get("variant"));

        EventDefinition closed = plan.events().stream().filter(e -> e.name().equals("newsletter_popup_closed")).findFirst().orElseThrow();
        assertEquals("custom", closed.domain(), "domain defaults to custom");
        assertEquals(List.of("browser", "server"), closed.sources());

        EventRegistry extended = loader.extend(standard, List.of(location));
        assertTrue(extended.isKnown("action_x_clicked"));
        assertTrue(extended.isKnown("product_viewed"));
    }

    @Test
    void rejectsACustomEventThatReusesAStandardName() throws IOException {
        List<String> problems = problems(plan("clash.yaml", """
                events:
                  product_viewed:
                    properties:
                      x: { type: string }
                """));
        assertTrue(problems.get(0).contains("is a standard catalog event"), problems.toString());
    }

    @Test
    void reportsMalformedYamlWithItsLine() throws IOException {
        List<String> problems = problems(plan("broken.yaml", "events:\n  a_event:\n    properties: [unclosed\n"));
        assertTrue(problems.get(0).contains("broken.yaml"), problems.toString());
        assertTrue(problems.get(0).contains("line"), problems.toString());
    }

    @Test
    void reportsEveryProblemAtOnce() throws IOException {
        List<String> problems = problems(plan("many.yaml", """
                events:
                  Bad_Name:
                    description: x
                  ok_event:
                    blocks: [basket, cart]
                    colour: red
                    properties:
                      untyped: { required: true }
                      kind: { type: enum, vocabulary: nope }
                      product.id: { required: true }
                      cart.id: { type: integer }
                """));
        String all = String.join("\n", problems);
        assertTrue(all.contains("Bad_Name: name must match"), all);
        assertTrue(all.contains("block \"basket\" does not exist"), all);
        assertTrue(all.contains("unknown key \"colour\""), all);
        assertTrue(all.contains("untyped: inline fields need a type"), all);
        assertTrue(all.contains("vocabulary \"nope\" does not exist"), all);
        assertTrue(all.contains("refines block \"product\", which this event does not list"), all);
        assertTrue(all.contains("may only set constraints, not type"), all);
    }

    @Test
    void rejectsTheSameEventInTwoPlansAndMissingFiles() throws IOException {
        String one = plan("one.yaml", "events:\n  dup_event:\n    description: one\n");
        String two = plan("two.yaml", "events:\n  dup_event:\n    description: two\n");
        assertTrue(problems(one, two).get(0).contains("defined more than once"));
        assertTrue(problems("file:///does/not/exist.yaml").get(0).contains("file not found"));
    }

    @Test
    void rejectsReservedInlineNames() throws IOException {
        List<String> problems = problems(plan("reserved.yaml", """
                events:
                  weird_event:
                    properties:
                      identity: { type: string }
                      product: { type: string }
                """));
        assertEquals(2, problems.size(), problems.toString());
    }

    @Test
    void noPlansMeansTheStandardCatalog() {
        assertSame(PlanLoader.Plan.EMPTY, loader.load(standard, List.of()));
    }
}
