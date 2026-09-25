# Changelog

## [0.2.5](https://github.com/moeezurrehman0/starling/compare/v0.2.4...v0.2.5) (2026-09-25)


### Bug fixes

* unblock the GitOps deploy path (S70-S74) ([#28](https://github.com/moeezurrehman0/starling/issues/28)) ([a4dc66d](https://github.com/moeezurrehman0/starling/commit/a4dc66d23e0b578cb4b2ffa5a1121d9e719819f3))

## [0.2.4](https://github.com/moeezurrehman0/starling/compare/v0.2.3...v0.2.4) (2026-09-25)


### Bug fixes

* have the tag-bump job open a pull request instead of pushing to main ([#26](https://github.com/moeezurrehman0/starling/issues/26)) ([346eabc](https://github.com/moeezurrehman0/starling/commit/346eabcd01551f877353c49282950a4dfeea5ec4))
* make the GitOps path deployable before the sandbox session depends on it ([#24](https://github.com/moeezurrehman0/starling/issues/24)) ([8b8395a](https://github.com/moeezurrehman0/starling/commit/8b8395a8171dd3dab787b695afa729f904300f3e))

## [0.2.3](https://github.com/moeezurrehman0/starling/compare/v0.2.2...v0.2.3) (2026-09-25)


### Bug fixes

* apply the S63 registry fix to the copy of it inside the matrix job ([#23](https://github.com/moeezurrehman0/starling/issues/23)) ([7d59d03](https://github.com/moeezurrehman0/starling/commit/7d59d03782574c4c1e77491088f5014016470a81))
* **ci:** tell an absent image apart from an unanswerable query ([#20](https://github.com/moeezurrehman0/starling/issues/20)) ([efbaf8d](https://github.com/moeezurrehman0/starling/commit/efbaf8d2f6b1c0d1151d71cf28b7567711b62d84))

## [0.2.2](https://github.com/moeezurrehman0/starling/compare/v0.2.1...v0.2.2) (2026-09-25)


### Bug fixes

* **release:** drop the component that no release could ever match ([#17](https://github.com/moeezurrehman0/starling/issues/17)) ([bd71441](https://github.com/moeezurrehman0/starling/commit/bd714416cc8bd034f4332c2961b61a9d7a911723))


### CI

* close the two residual gaps where a green check proved nothing ([#19](https://github.com/moeezurrehman0/starling/issues/19)) ([1664896](https://github.com/moeezurrehman0/starling/commit/1664896544c3894545a827fa1c83cb88d81a59c7))

## [0.2.1](https://github.com/moeezurrehman0/starling/compare/v0.2.0...v0.2.1) (2026-09-24)


### Bug fixes

* **release:** assert release bookkeeping and align the kind builder driver ([#14](https://github.com/moeezurrehman0/starling/issues/14)) ([cc50c76](https://github.com/moeezurrehman0/starling/commit/cc50c765ed62a5039dbd2ce18417443de7b69278))


### Build and dependencies

* **web:** typescript 6 and a registry assertion for Publish ([#16](https://github.com/moeezurrehman0/starling/issues/16)) ([ac06d44](https://github.com/moeezurrehman0/starling/commit/ac06d440fe0f5b6af4d8b0fac9dc841172de9b70))

## [0.2.0](https://github.com/moeezurrehman0/starling/compare/v0.1.0...v0.2.0) (2026-09-24)


### ⚠ BREAKING CHANGES

* ADR-0003 and ADR-0004 are superseded by ADR-0011/0012.

### Features

* **aiops:** deterministic Terraform risk commenter and read-only triage agent ([3fb75be](https://github.com/moeezurrehman0/starling/commit/3fb75bed60255f906d9d43df328230a11785e3a2))
* **build:** jlink + CDS distroless images with an evidence-based module allow-list ([91e13dc](https://github.com/moeezurrehman0/starling/commit/91e13dca1995abbc1bfaf5f93624cca97d5954cf))
* **contracts:** shared DynamoDB item records and table schemas ([acfc432](https://github.com/moeezurrehman0/starling/commit/acfc432c81c656110a2dde3a89340b8ba0aa0afe))
* **contracts:** time-ordered UUIDv7 id generation ([32fa8a5](https://github.com/moeezurrehman0/starling/commit/32fa8a5d93c9e681517595c5b45881e4cac2507e))
* **delivery:** k6 load harness, HPA scale-out and a canary gate proven by drill ([264f15e](https://github.com/moeezurrehman0/starling/commit/264f15ee9309a4b9f23073103716a56cc9d16ba0))
* **deploy:** ArgoCD app-of-apps, a kind cluster, and checks a schema cannot make ([af8cc48](https://github.com/moeezurrehman0/starling/commit/af8cc481f1cfe7cfb6af80d72d7e02cfea265178))
* **deploy:** one chart, two tiers, and the delta between them ([a3a241d](https://github.com/moeezurrehman0/starling/commit/a3a241d73684e086d8b2a0750943c66d45ae41fb))
* **fanout-worker:** materialise timelines from the tweets stream ([af42bf4](https://github.com/moeezurrehman0/starling/commit/af42bf4bf7d0e4f05674efd06070fcf7c11a988a))
* **gateway:** route, authenticate and rate limit at the edge ([64b0561](https://github.com/moeezurrehman0/starling/commit/64b0561713032baadc187a461010dc4e68c863ab))
* **infra:** terraform for two tiers, and the diff between them ([6d601dd](https://github.com/moeezurrehman0/starling/commit/6d601dd8fa1b56903dc41067bd45ef1ac159ed6f))
* **local:** Tier L compose stack with a single source of table truth ([e979fe8](https://github.com/moeezurrehman0/starling/commit/e979fe802c64b100ddb674c7e3f5ceb57c2f3f31))
* move operational data to DynamoDB and split the cache tier ([61b3499](https://github.com/moeezurrehman0/starling/commit/61b3499cc2ec96a00ca6af45b0459a383cd4bf48))
* **observability:** self-hosted metrics, logs and traces with correlation ([e2da82a](https://github.com/moeezurrehman0/starling/commit/e2da82a0933f071435aeeaba4f0de96b1179d930))
* **platform-aws:** discover the DynamoDB stream ARN from the table ([c53d2f6](https://github.com/moeezurrehman0/starling/commit/c53d2f6cb4cd0c874bfb95a724f345fab7e4094d))
* **probe:** measure what the playground permits, and test the measurement ([3b6c7d7](https://github.com/moeezurrehman0/starling/commit/3b6c7d7dcfbd6086af4a502fa71c2e9450174a40))
* **sandbox:** implement the 180-minute lifecycle and verify the teardown ([83e3abc](https://github.com/moeezurrehman0/starling/commit/83e3abc9c4adce7d9d8810541d3f1ceac386f087))
* **search:** index tweets into Postgres and serve full-text queries ([a15c54a](https://github.com/moeezurrehman0/starling/commit/a15c54a0a373bb16a134e1f1150ca9f4d9172fbb))
* **timeline-service:** hybrid home timeline across two cache tiers ([a2e0ac2](https://github.com/moeezurrehman0/starling/commit/a2e0ac27b31fdfe8b04d8c75bcff400a22f403a3))
* **tweet-service:** report like state on listings, in one round trip ([20e48f0](https://github.com/moeezurrehman0/starling/commit/20e48f00b8af8e655942eebd59c9d67962314d08))
* **user-service:** DynamoDB persistence, account lifecycle and the follow graph ([82bbc53](https://github.com/moeezurrehman0/starling/commit/82bbc5342c0ab0de97b1e21bf7932449fe0e8d03))
* **user-service:** expose a user's celebrity followees ([7d78657](https://github.com/moeezurrehman0/starling/commit/7d78657f634cd1bff0ef00a8dc24db72399db75b))
* **user-service:** HTTP API, RS256 token issuance and a published JWKS ([4341ab1](https://github.com/moeezurrehman0/starling/commit/4341ab1afb500d51fccb499f9300e3a8d39f583d))
* **web:** Next.js frontend over the gateway ([c01df84](https://github.com/moeezurrehman0/starling/commit/c01df84e13864973f3d14d1979ef46aac7fe5634))


### Bug fixes

* **aiops:** stop reporting wildcards that IAM will not let you scope ([#11](https://github.com/moeezurrehman0/starling/issues/11)) ([62c7766](https://github.com/moeezurrehman0/starling/commit/62c77667c0388ceb6a94dbae6e41a2ae0c4e9997))
* **ci:** grant the pull-request scope at the call site as well ([1a8a954](https://github.com/moeezurrehman0/starling/commit/1a8a954600c2e5a550dce0c638005da29026da6a))
* **ci:** grant the pull-request scope the PR-only gates need ([0a4a2d0](https://github.com/moeezurrehman0/starling/commit/0a4a2d0930b0ce604ff28d4e364d685cae15b625))
* **ci:** let the image check verify the one service that is not a web app ([e19331a](https://github.com/moeezurrehman0/starling/commit/e19331a1b1a4f5ae6ed23ae7dfaa125272c22258))
* **ci:** unblock the first pipeline run and close the checkov findings ([76cc718](https://github.com/moeezurrehman0/starling/commit/76cc718bdf0150a2bc82ba0b2e39d1d7d796a7e4))
* **deploy:** the celebrity cache was a copy of the ordinary one ([6ba711b](https://github.com/moeezurrehman0/starling/commit/6ba711bb8257b674ec8f8576e2ff624de5efcf79))
* **dev:** bring Tier L up end to end — eight bugs that only appear when you run it ([f122eee](https://github.com/moeezurrehman0/starling/commit/f122eeee4d43159998edc0d68d380c5879b7fd04))
* **docker:** make the CDS training run independent of the build host ([4f9c5bb](https://github.com/moeezurrehman0/starling/commit/4f9c5bb5e1ff1f565c93282b58520284ea330344))
* **fanout:** a consumer with no stream must not report Ready ([fce7313](https://github.com/moeezurrehman0/starling/commit/fce7313996be5ec14e3cc831d49c86d8f78f8a05))
* four defects that only running the images could find ([1f0024c](https://github.com/moeezurrehman0/starling/commit/1f0024cdf4ee80c00058eea0a8656385adc2d01c))
* **gateway:** route login, search and media, and open sign-up ([a88e144](https://github.com/moeezurrehman0/starling/commit/a88e144be9a637c9d5ed059129a28bbdf02d1d31))
* **security:** run the image scan, and fix what it found ([91311e3](https://github.com/moeezurrehman0/starling/commit/91311e309b34fa360e21d9a8da352a3fbdc14a4f))
* **web:** keep the session, and stop reporting likes as unknown ([47a7f86](https://github.com/moeezurrehman0/starling/commit/47a7f86442cb0992108678b4bc90bd19b4809550))


### Documentation

* **gap-register:** finalise the register and make its own rule enforceable ([62f9d66](https://github.com/moeezurrehman0/starling/commit/62f9d66e9e9c5b5c962035e6cbe83a3b1c740731))
* **gap-register:** record LocalStack ephemerality and the DOCKER_CONFIG/buildx trap ([8c348a3](https://github.com/moeezurrehman0/starling/commit/8c348a39de47d4fd016b812b849d227ee2e53885))
* give the README an entry point and verify the commands it prints ([ddc4cb5](https://github.com/moeezurrehman0/starling/commit/ddc4cb5ea28fc0c84b0775ec65149e339a20662e))
* name the trivy install script without implying it is ours ([507d45b](https://github.com/moeezurrehman0/starling/commit/507d45b76577c779c7ca048faec35250d0dd2071))
* record that the required check was required by nothing ([#6](https://github.com/moeezurrehman0/starling/issues/6)) ([3a61867](https://github.com/moeezurrehman0/starling/commit/3a61867d9897fe894ea66078fa190020cd59918e))
* record the product gaps and the silent-failure classes ([c634ead](https://github.com/moeezurrehman0/starling/commit/c634eade24cf98c4dc774f3fac1cd4f3899138db))
* system design, ADRs, delivery workflow and gap register ([2dbc900](https://github.com/moeezurrehman0/starling/commit/2dbc9003770b2a976c5d0b8adcddf1b657a3d996))


### Build and dependencies

* bump com.tngtech.archunit:archunit-junit5 from 1.4.1 to 1.5.0 ([#3](https://github.com/moeezurrehman0/starling/issues/3)) ([e65daec](https://github.com/moeezurrehman0/starling/commit/e65daec4af1bdd123cf1fd463f19b4d386c8ffb8))
* bump the aws sdk to 2.55.1 and move to the apache5 client ([#11](https://github.com/moeezurrehman0/starling/issues/11)) ([9a72080](https://github.com/moeezurrehman0/starling/commit/9a72080a3ef8fddf576184037fc98185559f003e))
* make the runtime base swappable and run the stack from images ([9e65f16](https://github.com/moeezurrehman0/starling/commit/9e65f16c74ea64ea828a15deae5fe48e587b8227))
* re-measure the image budgets, and fix the check that missed root ([b8eb3ca](https://github.com/moeezurrehman0/starling/commit/b8eb3caf1f524b95166f9f76b7abc68706c9ac3c))
* scaffold Gradle monorepo on Java 25 and Spring Boot 4.1.1 ([f5f2868](https://github.com/moeezurrehman0/starling/commit/f5f28689a2bd8b90df12ed8b9e696ac6124b428d))
* upgrade the aws provider to 6.66 across all nine files ([#10](https://github.com/moeezurrehman0/starling/issues/10)) ([c98d0f9](https://github.com/moeezurrehman0/starling/commit/c98d0f98f033923516e8f291ef2d36317f864dc1))
* upgrade the frontend to next 16 ([#12](https://github.com/moeezurrehman0/starling/issues/12)) ([90f957b](https://github.com/moeezurrehman0/starling/commit/90f957b9ad9a2c18b313eb5fcdd454ee1abec927))
* **web:** package the frontend image ([8c87d0a](https://github.com/moeezurrehman0/starling/commit/8c87d0aa4c430637f95c27740b7715b64c4fbfd9))


### CI

* bump the actions group across 1 directory with 17 updates ([#9](https://github.com/moeezurrehman0/starling/issues/9)) ([bda2351](https://github.com/moeezurrehman0/starling/commit/bda23511a257022b8fbe050403c3ee08bccacb28))
* **codeql:** skip loudly when Advanced Security is unavailable ([52e12d7](https://github.com/moeezurrehman0/starling/commit/52e12d738aa146571be7071b24ebd25cd632bb78))
* **docs:** fail the build on a broken Mermaid diagram ([f6941d3](https://github.com/moeezurrehman0/starling/commit/f6941d377ce5b783b9e4f6ecd1a7f5dbaa87bafc))
* lint workflow files before pushing them ([#10](https://github.com/moeezurrehman0/starling/issues/10)) ([2f77c9c](https://github.com/moeezurrehman0/starling/commit/2f77c9c622ee4d9818fd5e822e3c2fbf6e36d562))
* one pipeline, one required check ([90c88ee](https://github.com/moeezurrehman0/starling/commit/90c88ee9a8c12b685fa14de4b892399d5b176423))
