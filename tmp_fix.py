with open('app/src/main/java/com/nous/wxhook/ui/module/BackupHookLocal.kt') as f:
    lines = f.readlines()

AMPS = chr(38)  # &
for i, line in enumerate(lines):
    if 'sh -c' in line and 'cmd.replace' in line:
        lines[i] = '            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd + " ' + AMPS + '")).waitFor()\n'
        break

obj_close = None
result_idx = None
for i, line in enumerate(lines):
    if line.strip() == '}' and i > 200 and obj_close is None:
        obj_close = i
    if 'data class Result' in line:
        result_idx = i

if result_idx and obj_close and result_idx > obj_close:
    result_line = lines[result_idx]
    lines[result_idx] = ''
    lines.insert(obj_close, result_line)

with open('app/src/main/java/com/nous/wxhook/ui/module/BackupHookLocal.kt', 'w') as f:
    f.writelines(lines)
print('done')
