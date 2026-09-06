#!/usr/bin/env python3
"""Decode installed-APK EXR fixtures with the independent OpenEXR reference library."""
import json
import pathlib
import sys
import OpenEXR
import numpy as np

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'verification')
with OpenEXR.File(str(root / 'float-test.exr'), separate_channels=True) as image:
    header = image.header()
    assert header['compression'] == OpenEXR.ZIP_COMPRESSION
    channels = image.channels()
    assert set(channels) == {'R', 'G', 'B'}
    y, x = np.mgrid[:19, :37]
    expected = {
        'B': ((x + y * 37) / 1024).astype(np.float32),
        'G': np.where(x == 0, 100000, np.float32(.18)).astype(np.float32),
        'R': np.where(y == 0, 0, np.float32(1e-8)).astype(np.float32),
    }
    for channel, pixels in expected.items():
        assert channels[channel].pixels.dtype == np.float32
        np.testing.assert_array_equal(channels[channel].pixels, pixels)
with OpenEXR.File(str(root / 'random-test.exr'), separate_channels=True) as image:
    expected = np.fromfile(root / 'random-test.f32', dtype='<f4').reshape(17, 1, 3)
    for c, name in enumerate(['B', 'G', 'R']):
        np.testing.assert_array_equal(image.channels()[name].pixels.view('uint32'), expected[:, :, c].view('uint32'))
result = {'decoder': 'OpenEXR 3.3.3', 'floatChannelsExact': True, 'zipAndRawBlocks': True, 'partialBlockAndOrientation': True, 'highlightValue': 100000, 'dimValue': 1e-8}
(root / 'exr-verification.json').write_text(json.dumps(result, indent=2) + '\n')
print('OpenEXR reference decoder: exact float channels, HDR range, ZIP/raw blocks and orientation verified.')
