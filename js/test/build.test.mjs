import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';
import { fileURLToPath, URL } from 'node:url';
import { describe, expect, it } from 'vitest';

describe('plugin build output', () => {
  it('packages the settings page referenced by the manifest', () => {
    const project = fileURLToPath(new URL('..', import.meta.url));
    execFileSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['run', 'build'], {
      cwd: project,
      stdio: 'pipe',
    });
    const manifest = JSON.parse(readFileSync(join(project, 'dist/manifest.json'), 'utf8'));
    const outputPage = join(project, 'dist', manifest.settings.page);
    expect(existsSync(outputPage)).toBe(true);
    expect(readFileSync(outputPage, 'utf8')).toBe(readFileSync(join(project, manifest.settings.page), 'utf8'));
  });
});
