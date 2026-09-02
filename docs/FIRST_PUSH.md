# First repository push

Use these steps only after the group has agreed that this cleaned folder is the canonical baseline.

## 1. Verify the contents

From the repository root:

```bash
./tools/check.sh
git --version
find . -maxdepth 2 -type d | sort
```

Confirm that the folder does not contain `local.properties`, `.idea`, `.gradle`, `.kotlin`, `app/build`, APKs, recordings or personal photos.

## 2. Initialise the repository

```bash
git init
git branch -M main
git add .
git status
```

Review the complete staged-file list before committing. The initial commit should contain source code, the Gradle wrapper, tests and team documentation only.

```bash
git commit -m "chore: establish FloraGuide team baseline"
```

## 3. Connect the private remote

Create an empty private repository without an automatically generated README, licence or `.gitignore`, then run:

```bash
git remote add origin <repository-url>
git push -u origin main
```

If the remote already has commits, do not force-push over them without group agreement. Fetch the remote, inspect the history and merge or rebase deliberately.

## 4. Protect the baseline

After at least one other member has cloned, built and reviewed the baseline:

```bash
git tag -a proposal-baseline-v0.1 -m "Verified FloraGuide proposal baseline"
git push origin proposal-baseline-v0.1
```

Do not create the tag before team verification. A tag should identify a reproducible state, not merely the first upload.

## 5. Configure team identities

Every member should use their own Git identity:

```bash
git config user.name "Full Name"
git config user.email "University or GitHub-linked email"
```

Do not share one Git account. Confirm that commits appear under the correct member before substantial development begins.

## 6. Start normal development

- create an issue or agreed task;
- branch from `main`;
- make small outcome-oriented commits;
- open a pull request using the template;
- attach tests and physical-device evidence where relevant;
- obtain review before merge.

See `CONTRIBUTING.md` for the full workflow.
