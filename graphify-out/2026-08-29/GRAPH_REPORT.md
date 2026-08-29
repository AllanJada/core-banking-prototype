# Graph Report - banking-dilithium  (2026-08-29)

## Corpus Check
- 76 files · ~114,496 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 425 nodes · 771 edges · 24 communities (17 shown, 7 thin omitted)
- Extraction: 90% EXTRACTED · 10% INFERRED · 0% AMBIGUOUS · INFERRED: 78 edges (avg confidence: 0.79)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- React Frontend App
- Backend File-Transfer Domain
- Graphify Skill Docs
- Auth, Slip & PDF Generation
- Frontend NPM Dependencies
- Post-Quantum CryptoService
- Frontend tsconfig.app
- Frontend tsconfig.node
- README Architecture Concepts
- Maven Wrapper Script
- Spring Security Config
- Backend Exception Handling
- Frontend Oxlint Config
- Backend Smoke Test
- Spring Boot Entrypoint
- Frontend SVG Logos
- Skyline Background Branding
- Frontend tsconfig Root
- GraphML Export Doc
- SVG Export Doc
- Login Background Asset
- Hero Image Asset
- Java Base Package

## God Nodes (most connected - your core abstractions)
1. `CryptoService` - 23 edges
2. `FileTransferService` - 20 edges
3. `FileTransferResponse` - 18 edges
4. `compilerOptions` - 18 edges
5. `UserResponse` - 15 edges
6. `FileTransfer` - 15 edges
7. `compilerOptions` - 15 edges
8. `SlipRequest` - 12 edges
9. `User` - 12 edges
10. `UserRepositories` - 11 edges

## Surprising Connections (you probably didn't know these)
- `Graphify Trigger Instruction` --semantically_similar_to--> `Graphify Project Rules (CLAUDE.md)`  [INFERRED] [semantically similar]
  .claude/CLAUDE.md → CLAUDE.md
- `Payslip Thymeleaf Template` --conceptually_related_to--> `ML-DSA Bank File Transfer System`  [AMBIGUOUS]
  bank-backend/src/main/resources/templates/slip.html → README.md
- `AuthContext` --semantically_similar_to--> `userId-Per-Request Authentication Model`  [INFERRED] [semantically similar]
  bank-frontend/README.md → README.md
- `Demo Login Credentials` --semantically_similar_to--> `Seeded Institution Accounts`  [INFERRED] [semantically similar]
  bank-frontend/README.md → README.md
- `Native CLAUDE.md Integration` --conceptually_related_to--> `Graphify Project Rules (CLAUDE.md)`  [INFERRED]
  .claude/skills/graphify/references/hooks.md → CLAUDE.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Extraction Subagent Contract** — _claude_skills_graphify_references_extraction_spec_subagent_prompt, _claude_skills_graphify_references_extraction_spec_node_id_format, _claude_skills_graphify_references_extraction_spec_confidence_rubric, _claude_skills_graphify_references_extraction_spec_semantic_similarity_edge, _claude_skills_graphify_references_extraction_spec_source_file_rule [EXTRACTED 0.90]
- **Graphify Query Answering Flow** — _claude_skills_graphify_references_query_query, _claude_skills_graphify_references_query_constrained_query_expansion, _claude_skills_graphify_references_query_bfs_dfs_traversal, _claude_skills_graphify_references_query_networkx_fallback, _claude_skills_graphify_references_query_save_result, _claude_skills_graphify_references_query_work_memory [EXTRACTED 0.85]
- **Bank File Transfer System Core** — readme_mldsa_bank_file_transfer_system, readme_spring_boot_backend, readme_react_frontend, readme_authentication_model, readme_transfer_status_lifecycle, bank_frontend_readme_api_client [INFERRED 0.80]

## Communities (24 total, 7 thin omitted)

### Community 0 - "React Frontend App"
Cohesion: 0.05
Nodes (58): ApiErrorBody, downloadFile(), getInbox(), getOutbox(), handleResponse(), listUsers(), login(), sendFile() (+50 more)

### Community 1 - "Backend File-Transfer Domain"
Cohesion: 0.07
Nodes (30): FileTransferController, GetMapping, PostMapping, RequestMapping, RestController, RequestMapping, RestController, UserController (+22 more)

### Community 2 - "Graphify Skill Docs"
Cohesion: 0.06
Nodes (47): Graphify Trigger Instruction, Folder Watcher (--watch), graphify add (URL Ingest), FalkorDB Export, MCP Stdio Server, Neo4j Export, Token Reduction Benchmark, Wiki Export (+39 more)

### Community 3 - "Auth, Slip & PDF Generation"
Cohesion: 0.11
Nodes (21): AuthController, SlipController, GetMapping, PostMapping, FileTransferResponse, LoginRequest, SlipLineItem, SlipRequest (+13 more)

### Community 4 - "Frontend NPM Dependencies"
Cohesion: 0.05
Nodes (39): dependencies, @emotion/react, @emotion/styled, @mui/icons-material, @mui/material, react, react-dom, react-router-dom (+31 more)

### Community 5 - "Post-Quantum CryptoService"
Cohesion: 0.13
Nodes (6): CryptoService, Encapsulation, FileTransfer, java.security.KeyPair, java.security.PrivateKey, java.security.PublicKey

### Community 6 - "Frontend tsconfig.app"
Cohesion: 0.08
Nodes (23): compilerOptions, allowArbitraryExtensions, allowImportingTsExtensions, erasableSyntaxOnly, jsx, lib, module, moduleDetection (+15 more)

### Community 7 - "Frontend tsconfig.node"
Cohesion: 0.10
Nodes (19): compilerOptions, allowImportingTsExtensions, erasableSyntaxOnly, lib, module, moduleDetection, noEmit, noFallthroughCasesInSwitch (+11 more)

### Community 8 - "README Architecture Concepts"
Cohesion: 0.17
Nodes (17): Payslip Thymeleaf Template, Vite SPA HTML Entry, Typed API Client (client.ts), AuthContext, Demo Login Credentials, MUI Ledger Theme, SendFileDialog, TransferTable / StatusChip Components (+9 more)

### Community 9 - "Maven Wrapper Script"
Cohesion: 0.38
Nodes (8): mvnw script, clean(), die(), exec_maven(), hash_string(), set_java_home(), trim(), verbose()

### Community 10 - "Spring Security Config"
Cohesion: 0.38
Nodes (6): SecurityConfig, org.springframework.context.annotation.Bean, org.springframework.context.annotation.Configuration, org.springframework.security.config.annotation.web.builders.HttpSecurity, org.springframework.security.web.SecurityFilterChain, org.springframework.web.cors.CorsConfigurationSource

### Community 11 - "Backend Exception Handling"
Cohesion: 0.31
Nodes (5): ApiError, GlobalExceptionHandler, org.springframework.web.bind.annotation.ExceptionHandler, org.springframework.web.bind.annotation.RestControllerAdvice, org.springframework.web.multipart.MaxUploadSizeExceededException

### Community 12 - "Frontend Oxlint Config"
Cohesion: 0.22
Nodes (8): plugins, rules, react/only-export-components, react/rules-of-hooks, $schema, oxc, typescript, warn

### Community 13 - "Backend Smoke Test"
Cohesion: 0.60
Nodes (3): MldsaApplicationTests, org.junit.jupiter.api.Test, org.springframework.boot.test.context.SpringBootTest

### Community 15 - "Frontend SVG Logos"
Cohesion: 0.50
Nodes (4): Bank Frontend Favicon, UI Icon Sprite Sheet, React Logo, Vite Logo

### Community 16 - "Skyline Background Branding"
Cohesion: 0.67
Nodes (3): Skyline Background (bank-frontend/public/skyline-background.jpg), Bank Frontend Visual Branding, Corporate Finance Skyline Aesthetic

## Ambiguous Edges - Review These
- `ML-DSA Bank File Transfer System` → `Payslip Thymeleaf Template`  [AMBIGUOUS]
  bank-backend/src/main/resources/templates/slip.html · relation: conceptually_related_to
- `Bank Frontend Favicon` → `Vite Logo`  [AMBIGUOUS]
  bank-frontend/public/favicon.svg · relation: conceptually_related_to

## Knowledge Gaps
- **104 isolated node(s):** `org.learning:mldsa`, `SENT`, `DOWNLOADED`, `$schema`, `typescript` (+99 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **7 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `ML-DSA Bank File Transfer System` and `Payslip Thymeleaf Template`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Bank Frontend Favicon` and `Vite Logo`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `FileTransferService` connect `Backend File-Transfer Domain` to `Auth, Slip & PDF Generation`, `Post-Quantum CryptoService`?**
  _High betweenness centrality (0.022) - this node is a cross-community bridge._
- **Why does `CryptoService` connect `Post-Quantum CryptoService` to `Backend File-Transfer Domain`?**
  _High betweenness centrality (0.015) - this node is a cross-community bridge._
- **Why does `FileTransferResponse` connect `Auth, Slip & PDF Generation` to `Backend File-Transfer Domain`, `Post-Quantum CryptoService`?**
  _High betweenness centrality (0.013) - this node is a cross-community bridge._
- **What connects `org.learning:mldsa`, `SENT`, `DOWNLOADED` to the rest of the system?**
  _104 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `React Frontend App` be split into smaller, more focused modules?**
  _Cohesion score 0.05290490100616683 - nodes in this community are weakly interconnected._