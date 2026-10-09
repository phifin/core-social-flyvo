# Common-password snapshot

The additional printable 15–128 character entries (excluding addresses) come
from the NCSC top-100,000 dataset mirrored by SecLists at commit
`27c08068f849227f2fc1c8f7f00afae365957b96`:
https://github.com/danielmiessler/SecLists/blob/27c08068f849227f2fc1c8f7f00afae365957b96/Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt

Retrieved 2026-10-09. Shorter values are already rejected by the length policy.
Ten project seed values are retained. This is a local deny list, not a breach
lookup service. Review/update the pinned dataset before public launch and during
security maintenance; runtime authentication performs no network breach query.
