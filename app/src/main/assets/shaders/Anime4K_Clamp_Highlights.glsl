// MIT License

// Copyright (c) 2019-2021 bloc97
// All rights reserved.

// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:

// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.

// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

//!DESC Anime4K-v4.0-De-Ring-Compute-Statistics
//!HOOK MAIN
//!BIND HOOKED
//!SAVE STATSMAX_X
//!COMPONENTS 4

#define KERNELSIZE 5 //Kernel size, must be a positive odd integer.
#define KERNELHALFSIZE 2 //Half of the kernel size without remainder. Must be equal to trunc(KERNELSIZE/2).

float get_luma(vec4 rgba) {
	return dot(vec4(0.299, 0.587, 0.114, 0.0), rgba);
}

vec4 hook() {
	float gmax = 0.0;

	for (int i = 0; i < KERNELSIZE; i++) {
		float g = get_luma(HOOKED_texOff(vec2(float(i - KERNELHALFSIZE), 0.0)));
		gmax = max(g, gmax);
	}

	return vec4(gmax, 0.0, 0.0, 1.0);
}

//!DESC Anime4K-v4.0-De-Ring-Compute-Statistics
//!HOOK MAIN
//!BIND HOOKED
//!BIND STATSMAX_X
//!SAVE STATSMAX
//!COMPONENTS 4

#define KERNELSIZE 5 //Kernel size, must be a positive odd integer.
#define KERNELHALFSIZE 2 //Half of the kernel size without remainder. Must be equal to trunc(KERNELSIZE/2).

vec4 hook() {
	float gmax = 0.0;

	for (int i = 0; i < KERNELSIZE; i++) {
		float g = STATSMAX_X_texOff(vec2(0.0, float(i - KERNELHALFSIZE))).x;
		gmax = max(g, gmax);
	}

	return vec4(gmax, 0.0, 0.0, 1.0);
}

//!DESC Anime4K-v4.0-De-Ring-Clamp
//!HOOK PREKERNEL
//!BIND HOOKED
//!BIND STATSMAX

float get_luma(vec4 rgba) {
	return dot(vec4(0.299, 0.587, 0.114, 0.0), rgba);
}

vec4 hook() {
	vec4 col = HOOKED_tex(HOOKED_pos);
	float current_luma = get_luma(col);
	float new_luma = min(current_luma, STATSMAX_tex(HOOKED_pos).x);
	float diff = max(current_luma - new_luma, 0.0);

	// Preserve alpha and clamp RGB to non-negative to avoid chroma/blue underflow on Adreno.
	return vec4(max(col.rgb - vec3(diff), vec3(0.0)), col.a);
}