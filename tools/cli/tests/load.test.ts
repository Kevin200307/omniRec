// SPDX-License-Identifier: Apache-2.0
import { rmSync } from "node:fs";
import { join } from "node:path";
import { CatalogLoadError, loadCatalog, type CatalogIssue } from "../src";
import { FIXTURE_ROOT, removeTempFixtures, tempFixture, writeFiles } from "./helpers";

afterEach(removeTempFixtures);

/** Loads the catalog under `root` and returns the issues it fails with. */
function issuesFor(root: string): CatalogIssue[] {
  try {
    loadCatalog(join(root, "catalog"));
  } catch (error) {
    if (error instanceof CatalogLoadError) return error.issues;
    throw error;
  }
  throw new Error("expected the catalog to fail loading");
}

const messages = (issues: CatalogIssue[]) => issues.map((i) => `${i.file}: ${i.message}`);

const EVENT_HEADER = "# SPDX-License-Identifier: Apache-2.0\n";

describe("loadCatalog: valid catalog", () => {
  const catalog = loadCatalog(join(FIXTURE_ROOT, "catalog"));

  it("reads the header", () => {
    expect(catalog.catalogVersion).toBe(3);
    expect(catalog.schemaVersion).toBe("1.0");
    expect(catalog.domains.map((d) => d.id)).toEqual(["shop", "account"]);
  });

  it("orders events by domain declaration order, then by name", () => {
    expect(catalog.events.map((e) => e.name)).toEqual(["product_shared", "product_viewed", "account_linked"]);
  });

  it("resolves block fields and merges refinements", () => {
    const shared = catalog.events.find((e) => e.name === "product_shared")!;
    expect(shared.required).toEqual(["product.id", "channel"]);
    expect(shared.aliases).toEqual(["item_shared"]);
    expect(shared.version).toBe(2);

    const id = shared.fields.find((f) => f.path === "product.id")!;
    expect(id).toMatchObject({ type: "string", required: true, block: "product", refined: true });

    const price = shared.fields.find((f) => f.path === "product.price")!;
    expect(price).toMatchObject({ type: "money", minimum: 0, required: false, refined: false });

    const channel = shared.fields.find((f) => f.path === "channel")!;
    expect(channel).toMatchObject({ type: "enum", vocabulary: "share_channel", required: true, refined: true });
    expect(channel.block).toBeUndefined();
  });

  it("supports envelope refinements, control events and autocapture", () => {
    const linked = catalog.events.find((e) => e.name === "account_linked")!;
    expect(linked.control).toBe(true);
    expect(linked.required).toEqual(["identity.userId"]);
    expect(linked.blocks).toEqual([]);
    expect(catalog.events.find((e) => e.name === "product_viewed")!.autocapture).toBe(true);
  });

  it("records repository-relative file paths", () => {
    expect(catalog.events[0].file).toBe("catalog/events/shop/product_shared.yaml");
  });
});

describe("loadCatalog: rejected catalogs", () => {
  it("rejects a duplicate event name", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/account/product_viewed.yaml":
        EVENT_HEADER + "name: product_viewed\ndomain: account\nversion: 1\nsources: [browser]\ndescription: Duplicate.\n",
    });
    expect(messages(issuesFor(root)).join("\n")).toMatch(/"product_viewed" is already used by event product_viewed/);
  });

  it("rejects an alias that collides with another event name", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/account/account_linked.yaml":
        EVENT_HEADER +
        "name: account_linked\ndomain: account\nversion: 1\nsources: [server]\ndescription: Linked.\naliases: [product_viewed]\n",
    });
    expect(messages(issuesFor(root)).join("\n")).toMatch(/"product_viewed" is already used/);
  });

  it("rejects a name that breaks the naming rule", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/shop/AddToCart.yaml":
        EVENT_HEADER + "name: AddToCart\ndomain: shop\nversion: 1\nsources: [browser]\ndescription: Bad name.\n",
    });
    const issues = issuesFor(root);
    expect(issues[0].file).toBe("catalog/events/shop/AddToCart.yaml");
    expect(issues[0].message).toMatch(/\/name must match pattern/);
  });

  it("rejects a file whose name differs from the event name", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/shop/wrong_file.yaml":
        EVENT_HEADER + "name: something_else\ndomain: shop\nversion: 1\nsources: [browser]\ndescription: Mismatch.\n",
    });
    expect(messages(issuesFor(root)).join("\n")).toMatch(/file name must match name "something_else"/);
  });

  it("rejects an unknown block", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/shop/cart_viewed.yaml":
        EVENT_HEADER + "name: cart_viewed\ndomain: shop\nversion: 1\nsources: [browser]\ndescription: Cart.\nblocks: [cart]\n",
    });
    expect(messages(issuesFor(root)).join("\n")).toMatch(/block "cart" does not exist/);
  });

  it("rejects an unknown vocabulary", () => {
    const root = tempFixture();
    rmSync(join(root, "catalog/vocabularies/share_channel.yaml"));
    expect(messages(issuesFor(root)).join("\n")).toMatch(/uses vocabulary "share_channel", which does not exist/);
  });

  it("rejects an undeclared domain and a folder that does not match the domain", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/shop/order_placed.yaml":
        EVENT_HEADER + "name: order_placed\ndomain: orders\nversion: 1\nsources: [server]\ndescription: Order.\n",
    });
    const text = messages(issuesFor(root)).join("\n");
    expect(text).toMatch(/domain "orders" is not declared/);
    expect(text).toMatch(/event is in folder "shop" but declares domain "orders"/);
  });

  it("rejects refining a block the event does not list", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/account/account_linked.yaml":
        EVENT_HEADER +
        "name: account_linked\ndomain: account\nversion: 1\nsources: [server]\ndescription: Linked.\nproperties:\n  product.id: { required: true }\n",
    });
    expect(messages(issuesFor(root)).join("\n")).toMatch(/refines block "product", which this event does not list/);
  });

  it("rejects a refinement that tries to change the type", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/shop/product_viewed.yaml":
        EVENT_HEADER +
        "name: product_viewed\ndomain: shop\nversion: 1\nsources: [browser]\ndescription: Viewed.\nblocks: [product]\nproperties:\n  product.id: { type: integer }\n",
    });
    expect(messages(issuesFor(root)).join("\n")).toMatch(/may only set constraints/);
  });

  it("rejects an inline property without a type", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/shop/product_viewed.yaml":
        EVENT_HEADER +
        "name: product_viewed\ndomain: shop\nversion: 1\nsources: [browser]\ndescription: Viewed.\nproperties:\n  position: { required: true }\n",
    });
    expect(messages(issuesFor(root)).join("\n")).toMatch(/inline property "position" needs a type/);
  });

  it("rejects a constraint that does not fit the type", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/shop/product_viewed.yaml":
        EVENT_HEADER +
        "name: product_viewed\ndomain: shop\nversion: 1\nsources: [browser]\ndescription: Viewed.\nblocks: [product]\nproperties:\n  product.id: { minimum: 1 }\n",
    });
    expect(messages(issuesFor(root)).join("\n")).toMatch(/minimum\/maximum only apply to integer, number or money, not string/);
  });

  it("rejects an example that misses a required field or has the wrong type", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/shop/product_shared.yaml":
        EVENT_HEADER +
        [
          "name: product_shared",
          "domain: shop",
          "version: 1",
          "sources: [browser]",
          "description: Shared.",
          "blocks: [product]",
          "properties:",
          "  product.id: { required: true }",
          "  channel: { type: enum, vocabulary: share_channel, required: true }",
          "example:",
          "  product: { price: free, colour: red }",
          "  channel: carrier_pigeon",
          "",
        ].join("\n"),
    });
    const text = messages(issuesFor(root)).join("\n");
    expect(text).toMatch(/example: required field "product.id" is missing/);
    expect(text).toMatch(/example: "product.price" should be money/);
    expect(text).toMatch(/example: "product.colour" is not a field of block "product"/);
    expect(text).toMatch(/example: "channel" must be one of share_channel/);
  });

  it("reports YAML syntax errors with a line number", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/shop/product_viewed.yaml": EVENT_HEADER + "name: product_viewed\ndomain: [shop\n",
    });
    const issue = issuesFor(root).find((i) => i.file === "catalog/events/shop/product_viewed.yaml")!;
    expect(issue.message).toMatch(/^YAML:/);
    expect(issue.line).toBeGreaterThan(1);
  });

  it("rejects unknown keys in an event file", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/shop/product_viewed.yaml":
        EVENT_HEADER + "name: product_viewed\ndomain: shop\nversion: 1\nsources: [browser]\ndescription: Viewed.\ncategory: shop\n",
    });
    expect(messages(issuesFor(root)).join("\n")).toMatch(/must NOT have additional properties "category"/);
  });

  it("rejects event files outside a domain folder", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/loose_event.yaml":
        EVENT_HEADER + "name: loose_event\ndomain: shop\nversion: 1\nsources: [browser]\ndescription: Loose.\n",
    });
    expect(messages(issuesFor(root)).join("\n")).toMatch(/event files belong in catalog\/events\/<domain>\//);
  });

  it("reports every problem at once", () => {
    const root = tempFixture();
    writeFiles(root, {
      "catalog/events/shop/cart_viewed.yaml":
        EVENT_HEADER + "name: cart_viewed\ndomain: shop\nversion: 1\nsources: [browser]\ndescription: Cart.\nblocks: [cart]\n",
      "catalog/events/shop/order_placed.yaml":
        EVENT_HEADER + "name: order_placed\ndomain: orders\nversion: 1\nsources: [server]\ndescription: Order.\n",
    });
    expect(issuesFor(root).length).toBeGreaterThanOrEqual(3);
  });
});
