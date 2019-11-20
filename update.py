#!/usr/bin/env python3
# -*- coding: utf-8 -*-

# Copyright (c) 2019, Psiphon Inc.
# All rights reserved.
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.

import argparse
import subprocess
import sys


PRO_STRINGS = "pro-strings.xml"
FNAMES_TO_LOC = {
    "strings.xml": "app/src/main/res/values/strings.xml",
    "psiphon_android_library_strings.xml": "app/src/main/res/values/psiphon_android_library_strings.xml",
    "zirco_browser_strings.xml": "app/src/main/res/values/zirco_browser_strings.xml",
    "en.yaml": "i18n/feedback/en.yaml",
    PRO_STRINGS: "app/src/main/res/values/pro-strings.xml",
}


def update(branch, fnames):
    check_cmd = ("git", "rev-parse", "--verify", branch)
    print(f"Checking if branch '{branch}' exists...")
    subprocess.run(check_cmd, check=True)

    cmd_base = [
        "git",
        "checkout",
        "--patch",
        branch,
    ]

    for fname in fnames:
        floc = FNAMES_TO_LOC[fname]
        cmd = cmd_base + [floc]
        print(f"\n{floc}:")
        subprocess.run(cmd, check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Updates these awaiting-translation files from other branches")
    parser.add_argument("branch", help="the branch name where the files will be updated from; can be whatever git understands")
    parser.add_argument("fnames", nargs="+", choices=FNAMES_TO_LOC.keys(), help="files to update")
    args = parser.parse_args()

    update(args.branch, args.fnames)
