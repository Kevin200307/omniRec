export type RecommendationChoice = "aws-personalize" | "google-rec-ai" | "none";
export type SearchChoice = "algolia" | "none";
export type CacheChoice = "redis" | "none";

export interface ScaffoldOptions {
  projectName: string;
  recommendation: RecommendationChoice;
  search: SearchChoice;
  cache: CacheChoice;
}

const OMNIREC_VERSION = "0.1.0";

export function frontendPackageJson(opts: ScaffoldOptions): string {
  return JSON.stringify(
    {
      name: opts.projectName,
      version: "0.1.0",
      private: true,
      scripts: { dev: "next dev", build: "next build", start: "next start" },
      dependencies: {
        "@omnirec/core": OMNIREC_VERSION,
        "@omnirec/react": OMNIREC_VERSION,
        "@omnirec/react-ui": OMNIREC_VERSION,
        next: "^14.2.15",
        react: "^18.3.1",
        "react-dom": "^18.3.1",
      },
      devDependencies: {
        typescript: "^5.6.3",
        "@types/node": "^22.7.4",
        "@types/react": "^18.3.11",
        "@types/react-dom": "^18.3.0",
      },
    },
    null,
    2
  );
}

export const envExample = `NEXT_PUBLIC_OMNIREC_ENDPOINT=http://localhost:8080
NEXT_PUBLIC_OMNIREC_TENANT_ID=my-store
`;

export const providersTsx = `"use client";

import type { ReactNode } from "react";
import { OmnirecProvider } from "@omnirec/react";

export function Providers({ children }: { children: ReactNode }) {
  return (
    <OmnirecProvider
      tenantId={process.env.NEXT_PUBLIC_OMNIREC_TENANT_ID ?? "my-store"}
      endpoint={process.env.NEXT_PUBLIC_OMNIREC_ENDPOINT ?? "http://localhost:8080"}
      plugins={["dwellTime", "scrollDepth", "cart", "wishlist"]}
    >
      {children}
    </OmnirecProvider>
  );
}
`;

export const layoutTsx = `import type { ReactNode } from "react";
import { Providers } from "./providers";

export const metadata = { title: "My Store" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
`;

export const pageTsx = `"use client";

import { ProductImpression, SearchBar, RecommendationCarousel } from "@omnirec/react-ui";

export default function HomePage() {
  return (
    <main style={{ maxWidth: 960, margin: "0 auto", padding: 24 }}>
      <h1>My Store</h1>
      <SearchBar />
      <ProductImpression productId="sku-1">
        <div>Example product — replace with your catalog.</div>
      </ProductImpression>
      <RecommendationCarousel userId="demo-user" renderItem={(item) => <div key={item.productId}>{item.productId}</div>} />
    </main>
  );
}
`;

function starterArtifact(recommendation: RecommendationChoice, search: SearchChoice, cache: CacheChoice): string[] {
  const artifacts = ["omnirec-web"];
  if (recommendation === "aws-personalize") artifacts.push("omnirec-personalize-starter");
  if (recommendation === "google-rec-ai") artifacts.push("omnirec-google-recai-starter");
  if (search === "algolia") artifacts.push("omnirec-algolia-starter");
  if (cache === "redis") artifacts.push("omnirec-redis-starter");
  return artifacts;
}

export function backendPomXml(opts: ScaffoldOptions): string {
  const deps = starterArtifact(opts.recommendation, opts.search, opts.cache)
    .map(
      (artifact) => `    <dependency>
      <groupId>io.omnirec</groupId>
      <artifactId>${artifact}</artifactId>
      <version>${OMNIREC_VERSION}</version>
    </dependency>`
    )
    .join("\n");

  return `<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>${opts.projectName}-backend</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
  </parent>

  <properties>
    <java.version>17</java.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
${deps}
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
`;
}

export const backendApplicationJava = `package com.example.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
`;

export function backendApplicationYml(opts: ScaffoldOptions): string {
  return `server:
  port: 8080

omnirec:
  web:
    cors:
      allowed-origins:
        - http://localhost:3000
  recommendation:
    aws-personalize:
      enabled: ${opts.recommendation === "aws-personalize"}
    google-rec-ai:
      enabled: ${opts.recommendation === "google-rec-ai"}
  search:
    algolia:
      enabled: ${opts.search === "algolia"}
  cache:
    redis:
      enabled: ${opts.cache === "redis"}
`;
}

export function readmeMd(opts: ScaffoldOptions): string {
  return `# ${opts.projectName}

Scaffolded by \`create-omnirec-app\` — recommendation: **${opts.recommendation}**, search: **${opts.search}**, cache: **${opts.cache}**.

## Frontend

\`\`\`bash
cp .env.local.example .env.local
npm install
npm run dev
\`\`\`

## Backend

\`\`\`bash
cd backend
mvn spring-boot:run
\`\`\`

Every provider you didn't select above is simply absent from \`backend/pom.xml\` — add the matching \`io.omnirec:omnirec-*-starter\` dependency and flip its \`enabled\` flag in \`backend/src/main/resources/application.yml\` any time, no code changes required. See the Omnirec docs for available starters and their configuration keys.
`;
}
