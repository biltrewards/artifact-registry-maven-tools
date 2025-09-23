# Bilt AR Wagon Fork

go/arwagon

This describes how to use Bilt's GCP Artifact Registry maven wagon fork, which has much improved
performance. GCP promised they are treating poor Java AR performance as critical, but it is unclear
when significant improvements will actually be made.

Q: Would it be just as fast to use virtual with fallback to maven central in pom.xml?
Q: Does remote API have quota problems unlike remote?
Q: Does remote API not pull from central unlike remote?
Q: how to do jobrunr crap?



## Interface

On GitHub, we use remote API; everywhere else we just use maven central. This can be overriden by:

* **-Dcom.bilt.internal.arwagon.bilt-redirect=<value>**, where value is either:
  * none
  * standard
* **-Dcom.bilt.internal.arwagon.other-redirect=<value>**, where value is either:
  * none
  * remote
  * remote-api
  * maven-central


## Performance

On Github ubuntu-latest, with wagon 2.2.5, mvn 3.9.11 (default), mvn go:dependency-offline took:

* tests/maven-0-artifacts: 19:21 min
* tests/quarkus-starter: 39:04 min
* tests/payment-svc: 179 min

On local dev machine in VA, maven 3.8.7, tests/maven-0-artifacts (~450 downloads, excluding SHAs):

* maven central: 12s
* wagon 2.2.5, bilt-maven (mvn will fallback to maven central): 1:27 min
* wagon 2.2.5, bilt-maven, fallback to maven-central-cache: 5:32 min
* wagon 2.2.5, virtual: 13:08 min
* wagon 2.2.5 (edited), virtual, redirect non-bilt to remote AR API: 1:43min

See here for more numbers: https://docs.google.com/document/d/1z8XYDS-xTj2Y3EzUMFGM-lqa9zGcHnjo3B_KI-aiiBc/edit?tab=t.0#heading=h.hhx1fxwzd219

## Future Improvements

use latest maven with parallelization flags and bf collector (2-10x)

run builds in GCP network (2-10x)

prefetch SHAs/JARs (or lazily verify SHAs)

> Maven fetches pom, then pom.sha1, then eventually jar, then jar.sha1. We can prefetch on
> pom request, and/or compute the SHAs ourself and verify async.

better connection pooling / http2 (~15% from early experiments)
